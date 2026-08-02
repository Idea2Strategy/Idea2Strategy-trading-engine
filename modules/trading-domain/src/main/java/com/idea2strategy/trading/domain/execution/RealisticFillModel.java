package com.idea2strategy.trading.domain.execution;

import com.idea2strategy.trading.domain.order.OrderLifecycle;
import com.idea2strategy.trading.domain.order.OrderSide;
import com.idea2strategy.trading.domain.order.OrderStatus;
import com.idea2strategy.trading.domain.order.OrderTerms;
import com.idea2strategy.trading.domain.order.OrderType;
import java.math.BigDecimal;
import java.util.UUID;

public final class RealisticFillModel {
    public static final BigDecimal SLIPPAGE_RATE = new BigDecimal("0.0005");
    public static final BigDecimal FEE_RATE = new BigDecimal("0.002");

    public FillDecision evaluate(OrderLifecycle order, RecordedMarketSnapshot snapshot) {
        required(order, "order");
        required(snapshot, "snapshot");
        if (order.status() != OrderStatus.ACCEPTED && order.status() != OrderStatus.PARTIALLY_FILLED) {
            throw new IllegalStateException("only open orders can be evaluated");
        }
        if (!order.terms().instrumentId().equals(snapshot.instrumentId())) {
            throw new IllegalArgumentException("snapshot instrument does not match order");
        }
        if (snapshot.observedAt().isBefore(order.lastTransitionAt())) {
            throw new IllegalArgumentException("snapshot predates the current order state");
        }

        UUID decisionId = FillDecisionIdentity.decisionId(order, snapshot);
        String fingerprint = FillDecisionIdentity.fingerprint(order, snapshot);
        FillEligibility eligibility = eligibility(order.terms(), snapshot);
        if (eligibility != FillEligibility.ELIGIBLE) {
            return new FillDecision(decisionId, fingerprint, order.orderId(), order.version(), snapshot,
                    eligibility, null, snapshot.observedAt());
        }

        boolean buy = order.terms().side() == OrderSide.BUY;
        BigDecimal liquidity = buy ? snapshot.askSize() : snapshot.bidSize();
        if (liquidity.signum() == 0) {
            return new FillDecision(decisionId, fingerprint, order.orderId(), order.version(), snapshot,
                    FillEligibility.NO_OBSERVED_LIQUIDITY, null, snapshot.observedAt());
        }
        BigDecimal remaining = order.terms().quantity().subtract(order.cumulativeFilledQuantity()).stripTrailingZeros();
        BigDecimal quantity = remaining.min(liquidity).stripTrailingZeros();
        BigDecimal reference = buy ? snapshot.askPrice() : snapshot.bidPrice();
        BigDecimal slipped = buy
                ? reference.multiply(BigDecimal.ONE.add(SLIPPAGE_RATE))
                : reference.multiply(BigDecimal.ONE.subtract(SLIPPAGE_RATE));
        BigDecimal price = limitProtected(order.terms(), slipped).stripTrailingZeros();
        BigDecimal notional = quantity.multiply(price).stripTrailingZeros();
        BigDecimal referenceNotional = quantity.multiply(reference);
        BigDecimal slippage = notional.subtract(referenceNotional).abs().stripTrailingZeros();
        BigDecimal fee = notional.multiply(FEE_RATE).stripTrailingZeros();
        VirtualFill fill = new VirtualFill(FillDecisionIdentity.fillId(decisionId), quantity, reference, price,
                notional, slippage, fee, quantity.compareTo(remaining) < 0, snapshot.observedAt());
        return new FillDecision(decisionId, fingerprint, order.orderId(), order.version(), snapshot,
                FillEligibility.ELIGIBLE, fill, snapshot.observedAt());
    }

    private static FillEligibility eligibility(OrderTerms terms, RecordedMarketSnapshot snapshot) {
        boolean triggered = switch (terms.type()) {
            case STOP, STOP_LIMIT -> terms.side() == OrderSide.BUY
                    ? snapshot.lastTradePrice().compareTo(terms.stopPrice()) >= 0
                    : snapshot.lastTradePrice().compareTo(terms.stopPrice()) <= 0;
            case TRAILING_STOP -> trailingTriggered(terms, snapshot);
            default -> true;
        };
        if (!triggered) return FillEligibility.TRIGGER_NOT_REACHED;

        if (terms.type() == OrderType.LIMIT || terms.type() == OrderType.STOP_LIMIT) {
            boolean marketable = terms.side() == OrderSide.BUY
                    ? snapshot.askPrice().compareTo(terms.limitPrice()) <= 0
                    : snapshot.bidPrice().compareTo(terms.limitPrice()) >= 0;
            if (!marketable) return FillEligibility.PRICE_LIMIT_NOT_MARKETABLE;
        }
        return FillEligibility.ELIGIBLE;
    }

    private static boolean trailingTriggered(OrderTerms terms, RecordedMarketSnapshot snapshot) {
        if (snapshot.trailingReferencePrice() == null) {
            return false;
        }
        BigDecimal boundary = terms.side() == OrderSide.SELL
                ? snapshot.trailingReferencePrice().multiply(BigDecimal.ONE.subtract(terms.trailPercent()))
                : snapshot.trailingReferencePrice().multiply(BigDecimal.ONE.add(terms.trailPercent()));
        return terms.side() == OrderSide.SELL
                ? snapshot.lastTradePrice().compareTo(boundary) <= 0
                : snapshot.lastTradePrice().compareTo(boundary) >= 0;
    }

    private static BigDecimal limitProtected(OrderTerms terms, BigDecimal slipped) {
        if (terms.type() != OrderType.LIMIT && terms.type() != OrderType.STOP_LIMIT) return slipped;
        return terms.side() == OrderSide.BUY ? slipped.min(terms.limitPrice()) : slipped.max(terms.limitPrice());
    }

    private static <T> T required(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " must not be null");
        return value;
    }
}
