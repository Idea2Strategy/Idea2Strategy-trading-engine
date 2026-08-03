package com.idea2strategy.trading.persistence.candidate;

import com.idea2strategy.trading.application.candidate.ScopedCandidateComposer;
import com.idea2strategy.trading.application.candidate.ScopedCompositionResult;
import com.idea2strategy.trading.application.port.BotEventStore;
import com.idea2strategy.trading.application.port.OrderIntentBatchStore;
import com.idea2strategy.trading.application.port.OrderLifecycleStore;
import com.idea2strategy.trading.application.port.ResourceReservationStore;
import com.idea2strategy.trading.application.port.TradingPolicyRegistry;
import com.idea2strategy.trading.domain.budget.BudgetReasonCode;
import com.idea2strategy.trading.domain.candidate.CandidateBatch;
import com.idea2strategy.trading.domain.candidate.CandidateOrder;
import com.idea2strategy.trading.domain.eligibility.InstrumentFractionalPolicy;
import com.idea2strategy.trading.domain.eligibility.OrderEligibilityDecision;
import com.idea2strategy.trading.domain.eligibility.OrderEligibilityPolicy;
import com.idea2strategy.trading.domain.eligibility.OrderEligibilityRequest;
import com.idea2strategy.trading.domain.eligibility.OrderPositionEffect;
import com.idea2strategy.trading.domain.eligibility.QuantityMode;
import com.idea2strategy.trading.domain.event.BotEvent;
import com.idea2strategy.trading.domain.event.BotEventAppend;
import com.idea2strategy.trading.domain.event.BotEventType;
import com.idea2strategy.trading.domain.intent.IntentDecision;
import com.idea2strategy.trading.domain.intent.OrderIntent;
import com.idea2strategy.trading.domain.intent.OrderIntentBatch;
import com.idea2strategy.trading.domain.intent.OrderIntentBatchFactory;
import com.idea2strategy.trading.domain.intent.OrderIntentBatchRequest;
import com.idea2strategy.trading.domain.intent.OrderIntentRequest;
import com.idea2strategy.trading.domain.order.OrderLifecycle;
import com.idea2strategy.trading.domain.order.OrderLifecycleFactory;
import com.idea2strategy.trading.domain.order.OrderPolicyPins;
import com.idea2strategy.trading.domain.order.OrderScope;
import com.idea2strategy.trading.domain.order.OrderSide;
import com.idea2strategy.trading.domain.order.OrderType;
import com.idea2strategy.trading.domain.order.TimeInForce;
import com.idea2strategy.trading.domain.policy.EffectiveTradingPolicy;
import com.idea2strategy.trading.domain.reservation.LotReservationAllocation;
import com.idea2strategy.trading.domain.reservation.ReservationComponentLink;
import com.idea2strategy.trading.domain.reservation.ReservationOpening;
import com.idea2strategy.trading.domain.reservation.ReservationPolicyPins;
import com.idea2strategy.trading.domain.reservation.ReservationPricing;
import com.idea2strategy.trading.domain.reservation.ResourceReservation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The production composition of a scoped candidate batch: the real path RT4 promised, replacing the
 * fake per-candidate pipeline for every batch that carries partition scope.
 *
 * <p>One claimed batch becomes, in one transaction: a canonical order intent batch with a decision
 * per candidate (F02 affordability and F03 eligibility), an accepted canonical order per executable
 * intent, and the resource reservation each order's fill will draw on — cash buying power for a
 * buy, FIFO-locked lot quantity for a sell — attached to the order's component.
 *
 * <p><strong>Redelivery converges.</strong> Every identity and timestamp is derived from the
 * batch's own evaluation ({@code composedAt} is the batch's {@code createdAt}, never a clock), so
 * the second delivery of the same batch re-derives the same intent batch, the same orders, the same
 * reservations, and every store involved is create-or-load.
 *
 * <p><strong>Decision rules, pinned.</strong>
 *
 * <ul>
 *   <li>Sizing reference price: the candidate's own {@code limitPrice} when it names one, otherwise
 *       the latest canonical fill reference price of the instrument observed before the batch was
 *       created — the same engine-wide mark rule the position valuation and the virtual liquidation
 *       quote already share. An instrument no fill has ever touched and no limit price names is
 *       {@code REJECTED NO_REFERENCE_PRICE}: fail closed, no invented number.
 *   <li>Affordability (buys): spendable cash is the bot budget projection's available cash net of
 *       active reservations, capped by the partition's remaining budget. A missing or unvalued
 *       projection rejects every buy with {@code POSITION_VALUATION_UNAVAILABLE}, F02's own
 *       fail-closed rule. When the batch's total estimated cost (notional + fixed 5 bps slippage +
 *       fee + buying power buffer) exceeds spendable cash, every buy is reduced proportionally —
 *       {@code COMMON_FUNDS_PROPORTIONAL_REDUCTION} — and a buy reduced to nothing is rejected with
 *       {@code NO_AVAILABLE_SHARED_FUNDS}.
 *   <li>Sells reduce long exposure only. Basic candidates never open shorts, so a sell beyond the
 *       flow's open FIFO remainder is reduced to what is actually held, and a sell against nothing
 *       is {@code REJECTED NO_OPEN_POSITION} — never an implicit short.
 *   <li>Order contract: a candidate naming a limit price asks for a LIMIT, otherwise MARKET; both
 *       DAY, the only time in force the version 2 candidate contract can express.
 * </ul>
 */
@Component
public class PostgresScopedCandidateComposition implements ScopedCandidateComposer {

    /** The fixed platform slippage the canonical CHECK pins to five basis points. */
    private static final BigDecimal FIXED_SLIPPAGE_RATE = new BigDecimal("0.0005");

    private static final BigDecimal BPS = new BigDecimal("10000");
    private static final int AMOUNT_SCALE = 8;
    private static final int FACTOR_SCALE = 18;

    private static final String LATEST_MARK = """
            select f.reference_price, f.reference_observed_at, f.reference_market_hash
            from trading.fills f
            join trading.orders o on o.id = f.order_id
            where o.instrument_id = :instrumentId and f.reference_observed_at <= :observedBefore
            order by f.reference_observed_at desc, f.recorded_at desc, f.id desc
            limit 1
            """;

    private static final String OPEN_FIFO_LOTS = """
            select lot.id, lot.opened_at,
                   proj.remaining_quantity - proj.active_reserved_quantity as available_quantity
            from trading.position_lots lot
            join trading.position_lot_projections proj on proj.position_lot_id = lot.id
            where lot.bot_id = :botId and lot.flow_id = :flowId
              and lot.instrument_id = :instrumentId and lot.lot_side = 'LONG'
              and proj.remaining_quantity > proj.active_reserved_quantity
            order by lot.opened_at, lot.id
            """;

    private static final String LAUNCH_PINS = """
            select broker_rules_version, precision_rules_version, fee_policy_id,
                   buying_power_buffer_policy_id, currency_code
            from bot.launch_configurations
            where bot_id = :botId
            """;

    private static final String BUDGET_ROWS = """
            select bot.available_cash_amount as bot_available,
                   bot.active_reservation_amount as bot_reserved,
                   bot.valuation_status as bot_status,
                   p.budget_cap_amount as partition_cap,
                   p.invested_amount as partition_invested,
                   p.active_reservation_amount as partition_reserved,
                   p.valuation_status as partition_status
            from trading.bot_budget_projections bot
            join trading.partition_budget_projections p on p.partition_id = :partitionId
            where bot.bot_id = :botId
            """;

    private final JdbcClient jdbc;
    private final OrderIntentBatchStore intents;
    private final OrderLifecycleStore orders;
    private final ResourceReservationStore reservations;
    private final BotEventStore events;
    private final TradingPolicyRegistry policies;
    private final OrderEligibilityPolicy eligibility = new OrderEligibilityPolicy();
    private final OrderIntentBatchFactory intentFactory = new OrderIntentBatchFactory();
    private final OrderLifecycleFactory orderFactory = new OrderLifecycleFactory();

    public PostgresScopedCandidateComposition(
            JdbcClient jdbc,
            OrderIntentBatchStore intents,
            OrderLifecycleStore orders,
            ResourceReservationStore reservations,
            BotEventStore events,
            TradingPolicyRegistry policies) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.intents = Objects.requireNonNull(intents, "intents");
        this.orders = Objects.requireNonNull(orders, "orders");
        this.reservations = Objects.requireNonNull(reservations, "reservations");
        this.events = Objects.requireNonNull(events, "events");
        this.policies = Objects.requireNonNull(policies, "policies");
    }

    @Override
    @Transactional
    public ScopedCompositionResult compose(CandidateBatch batch) {
        Objects.requireNonNull(batch, "batch");
        if (!batch.carriesPartitionScope()) {
            throw new IllegalArgumentException("only a scoped batch can become canonical");
        }
        Instant composedAt = batch.createdAt();
        OrderScope scope = new OrderScope(batch.botId(), batch.partitionId());
        LaunchPins launch = launchPins(batch.botId());
        EffectiveTradingPolicy.Fee fee = policies.feePolicyAt(composedAt);
        EffectiveTradingPolicy.BuyingPowerBuffer buffer = policies.buyingPowerBufferPolicyAt(composedAt);

        Map<UUID, Sizing> sizings = new LinkedHashMap<>();
        List<OrderIntentRequest> requests = new ArrayList<>();
        BigDecimal totalBuyCost = BigDecimal.ZERO;
        for (CandidateOrder candidate : batch.candidates()) {
            Sizing sizing = size(batch, candidate, composedAt, fee, buffer);
            sizings.put(candidate.candidateId(), sizing);
            if (sizing.rejectionReason() == null && sizing.side() == OrderSide.BUY) {
                totalBuyCost = totalBuyCost.add(sizing.estimatedCost());
            }
        }

        BigDecimal spendable = spendableCash(batch.botId(), batch.partitionId());
        BigDecimal reductionFactor = null;
        if (spendable == null) {
            // F02: without a complete valuation there is no budget to allocate from.
            sizings.values().stream()
                    .filter(sizing -> sizing.rejectionReason() == null && sizing.side() == OrderSide.BUY)
                    .forEach(sizing -> sizing.reject(BudgetReasonCode.POSITION_VALUATION_UNAVAILABLE.name()));
        } else if (totalBuyCost.compareTo(spendable) > 0) {
            reductionFactor = spendable.divide(totalBuyCost, FACTOR_SCALE, RoundingMode.DOWN);
        }

        for (CandidateOrder candidate : batch.candidates()) {
            requests.add(intentRequest(candidate, sizings.get(candidate.candidateId()), reductionFactor));
        }

        OrderIntentBatch stored = intents.createOrLoad(intentFactory.create(new OrderIntentBatchRequest(
                batch.botId(), batch.partitionId(), batch.sourceEventId(), batch.evaluationId(),
                batch.batchId(), composedAt, requests)));

        int ordersComposed = 0;
        for (OrderIntent intent : stored.intents()) {
            if (intent.request().decision() == IntentDecision.REJECTED) {
                continue;
            }
            composeOrder(scope, launch, fee, buffer, intent,
                    sizings.get(intent.request().candidateId()), composedAt);
            ordersComposed++;
        }

        int approved = 0;
        int reduced = 0;
        int rejected = 0;
        for (OrderIntent intent : stored.intents()) {
            switch (intent.request().decision()) {
                case APPROVED -> approved++;
                case REDUCED -> reduced++;
                case REJECTED -> rejected++;
            }
        }
        return new ScopedCompositionResult(stored.batchId(), approved, reduced, rejected, ordersComposed);
    }

    // ------------------------------------------------------------------ sizing

    /**
     * Everything about one candidate that is decided before batch-level affordability: side,
     * contract, eligibility, reference price and estimated cost. Mutable only in its rejection,
     * which later stages may add but never remove.
     */
    private Sizing size(
            CandidateBatch batch,
            CandidateOrder candidate,
            Instant composedAt,
            EffectiveTradingPolicy.Fee fee,
            EffectiveTradingPolicy.BuyingPowerBuffer buffer) {
        OrderSide side = OrderSide.valueOf(candidate.side());
        Sizing sizing = new Sizing(side, candidate.quantity());

        QuantityMode mode = candidate.quantity().stripTrailingZeros().scale() > 0
                ? QuantityMode.FRACTIONAL_SHARES
                : QuantityMode.WHOLE_SHARES;
        OrderEligibilityDecision decision = eligibility.evaluate(new OrderEligibilityRequest(
                // The platform publishes no per-instrument fractional enablement yet, so no
                // instrument is fractional-enabled: a fractional candidate fails closed here.
                new InstrumentFractionalPolicy(candidate.instrumentId(), false, "platform-default:v1"),
                side == OrderSide.BUY ? OrderPositionEffect.INCREASE_LONG : OrderPositionEffect.REDUCE_LONG,
                candidate.limitPrice() == null ? OrderType.MARKET : OrderType.LIMIT,
                TimeInForce.DAY,
                mode,
                candidate.quantity()));
        if (!decision.reasons().isEmpty()) {
            sizing.reject(decision.reasons().getFirst().name());
            return sizing;
        }

        Mark mark = referencePrice(candidate, composedAt);
        if (mark == null) {
            sizing.reject("NO_REFERENCE_PRICE");
            return sizing;
        }
        sizing.price(mark);

        if (side == OrderSide.BUY) {
            BigDecimal notional = amount(candidate.quantity().multiply(mark.price()));
            sizing.costs(
                    notional,
                    amount(notional.multiply(FIXED_SLIPPAGE_RATE)),
                    amount(notional.multiply(rate(fee.feeRateBps()))),
                    amount(notional.multiply(rate(buffer.bufferBps()))));
        } else {
            List<LotReservationAllocation> lots = openLots(batch, candidate, sizing.requestedQuantity());
            BigDecimal available = lots.stream()
                    .map(LotReservationAllocation::reservedQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (available.signum() == 0) {
                sizing.reject("NO_OPEN_POSITION");
            } else {
                sizing.lots(lots, available.min(sizing.requestedQuantity()));
            }
        }
        return sizing;
    }

    private OrderIntentRequest intentRequest(
            CandidateOrder candidate, Sizing sizing, BigDecimal reductionFactor) {
        OrderSide side = sizing.side();
        OrderPositionEffect effect = side == OrderSide.BUY
                ? OrderPositionEffect.INCREASE_LONG
                : OrderPositionEffect.REDUCE_LONG;
        OrderType type = candidate.limitPrice() == null ? OrderType.MARKET : OrderType.LIMIT;

        IntentDecision decision;
        String reason;
        BigDecimal finalQuantity;
        if (sizing.rejectionReason() != null) {
            decision = IntentDecision.REJECTED;
            reason = sizing.rejectionReason();
            finalQuantity = null;
        } else if (side == OrderSide.BUY) {
            finalQuantity = sizing.requestedQuantity();
            if (reductionFactor != null) {
                finalQuantity = reduceQuantity(sizing.requestedQuantity(), reductionFactor);
            }
            if (finalQuantity.signum() == 0) {
                decision = IntentDecision.REJECTED;
                reason = BudgetReasonCode.NO_AVAILABLE_SHARED_FUNDS.name();
                finalQuantity = null;
            } else if (finalQuantity.compareTo(sizing.requestedQuantity()) < 0) {
                decision = IntentDecision.REDUCED;
                reason = BudgetReasonCode.COMMON_FUNDS_PROPORTIONAL_REDUCTION.name();
                sizing.executableQuantity(finalQuantity);
            } else {
                decision = IntentDecision.APPROVED;
                reason = "ELIGIBLE";
                sizing.executableQuantity(finalQuantity);
            }
        } else {
            finalQuantity = sizing.executableQuantity();
            if (finalQuantity.compareTo(sizing.requestedQuantity()) < 0) {
                decision = IntentDecision.REDUCED;
                reason = "REDUCED_TO_OPEN_POSITION";
            } else {
                decision = IntentDecision.APPROVED;
                reason = "ELIGIBLE";
            }
        }
        return new OrderIntentRequest(
                candidate.candidateId(), candidate.flowId(), candidate.instrumentId(), side, effect,
                type, TimeInForce.DAY, sizing.requestedQuantity(), candidate.limitPrice(), null,
                null, decision, reason, finalQuantity);
    }

    /**
     * A whole-share buy stays whole through a proportional reduction: the factor applies and the
     * result floors, because rounding a reduction up would spend money the reduction exists to
     * protect.
     */
    private static BigDecimal reduceQuantity(BigDecimal requested, BigDecimal factor) {
        BigDecimal reduced = requested.multiply(factor);
        boolean whole = requested.stripTrailingZeros().scale() <= 0;
        return reduced.setScale(whole ? 0 : AMOUNT_SCALE, RoundingMode.DOWN);
    }

    // ------------------------------------------------------------------ canonical writes

    private void composeOrder(
            OrderScope scope,
            LaunchPins launch,
            EffectiveTradingPolicy.Fee fee,
            EffectiveTradingPolicy.BuyingPowerBuffer buffer,
            OrderIntent intent,
            Sizing sizing,
            Instant composedAt) {
        OrderIntentRequest request = intent.request();
        BigDecimal quantity = request.finalQuantity();

        BotEvent accepted = events.appendOrLoad(new BotEventAppend(
                scope.botId(), BotEventType.ORDER_ACCEPTED,
                "candidate-order-accepted:" + intent.intentId(), intent.intentId(), null,
                composedAt, composedAt,
                "{\"intentId\":\"" + intent.intentId() + "\",\"origin\":\"FLOW_EVALUATION\"}"));
        OrderLifecycle lifecycle = orders.createOrLoad(
                new com.idea2strategy.trading.domain.order.OrderPlacement(
                        orderFactory.accepted(new com.idea2strategy.trading.domain.order.OrderTerms(
                                intent.intentId(), request.candidateId(), request.instrumentId(),
                                request.side(), quantity, request.orderType(), request.timeInForce(),
                                request.limitPrice(), null, null, null), composedAt),
                        scope,
                        new OrderPolicyPins(
                                launch.feePolicyId(), launch.brokerRulesVersion(),
                                launch.precisionRulesVersion(),
                                OrderIntentBatchFactory.COMPOSITION_RULES_VERSION),
                        accepted.eventId(),
                        List.of(new com.idea2strategy.trading.domain.order.OrderComponent(
                                intent.intentId(), quantity, 1))));

        BotEvent reserved = events.appendOrLoad(new BotEventAppend(
                scope.botId(), BotEventType.RESERVATION_CREATED,
                "candidate-reservation-created:" + intent.intentId(), intent.intentId(), null,
                composedAt, composedAt,
                "{\"intentId\":\"" + intent.intentId() + "\"}"));
        ResourceReservation reservation;
        if (request.side() == OrderSide.BUY) {
            Mark mark = sizing.mark();
            BigDecimal notional = amount(quantity.multiply(mark.price()));
            BigDecimal slippage = amount(notional.multiply(FIXED_SLIPPAGE_RATE));
            BigDecimal feeAmount = amount(notional.multiply(rate(fee.feeRateBps())));
            BigDecimal bufferAmount = amount(notional.multiply(rate(buffer.bufferBps())));
            BigDecimal reservedAmount = notional.add(slippage).add(feeAmount).add(bufferAmount);
            reservation = reservations.createOrLoad(new ReservationOpening(
                    ResourceReservation.cash(
                            intent.intentId(), launch.currencyCode(), reservedAmount, composedAt),
                    scope, request.flowId(), reserved.eventId(),
                    ReservationPolicyPins.buyingPower(
                            launch.bufferPolicyId(), launch.feePolicyId(),
                            launch.precisionRulesVersion()),
                    ReservationPricing.buyingPower(
                            mark.price(), mark.observedAt(), mark.marketHash(),
                            notional, slippage, feeAmount, bufferAmount)));
        } else {
            reservation = reservations.createOrLoad(new ReservationOpening(
                    ResourceReservation.positionQuantity(
                            intent.intentId(), request.instrumentId(), quantity,
                            takeFifo(sizing.lots(), quantity), composedAt),
                    scope, request.flowId(), reserved.eventId(),
                    ReservationPolicyPins.positionQuantity(launch.precisionRulesVersion()),
                    ReservationPricing.none()));
        }

        UUID componentId = jdbc.sql(
                        "select id from trading.order_components where order_id = :orderId "
                                + "and intent_id = :intentId")
                .param("orderId", lifecycle.orderId())
                .param("intentId", intent.intentId())
                .query(UUID.class)
                .single();
        reservations.attachToOrderComponent(
                new ReservationComponentLink(scope, reservation.reservationId(), componentId));
    }

    // ------------------------------------------------------------------ reads

    private LaunchPins launchPins(UUID botId) {
        return jdbc.sql(LAUNCH_PINS)
                .param("botId", botId)
                .query((rs, row) -> new LaunchPins(
                        rs.getString("broker_rules_version"),
                        rs.getString("precision_rules_version"),
                        rs.getObject("fee_policy_id", UUID.class),
                        rs.getObject("buying_power_buffer_policy_id", UUID.class),
                        rs.getString("currency_code").trim()))
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "bot " + botId + " has no launch configuration to pin orders to"));
    }

    /** Spendable cash for this batch's buys, or null when the valuation is not complete. */
    private BigDecimal spendableCash(UUID botId, UUID partitionId) {
        return jdbc.sql(BUDGET_ROWS)
                .param("botId", botId)
                .param("partitionId", partitionId)
                .query((rs, row) -> {
                    if (!"VALUED".equals(rs.getString("bot_status"))
                            || !"VALUED".equals(rs.getString("partition_status"))) {
                        return null;
                    }
                    BigDecimal botSpendable = rs.getBigDecimal("bot_available")
                            .subtract(rs.getBigDecimal("bot_reserved"));
                    BigDecimal partitionSpendable = rs.getBigDecimal("partition_cap")
                            .subtract(rs.getBigDecimal("partition_invested"))
                            .subtract(rs.getBigDecimal("partition_reserved"));
                    return botSpendable.min(partitionSpendable).max(BigDecimal.ZERO);
                })
                .optional()
                .orElse(null);
    }

    private Mark referencePrice(CandidateOrder candidate, Instant composedAt) {
        if (candidate.limitPrice() != null) {
            // The candidate's own price is the sizing basis; its hash records that derivation.
            return new Mark(
                    candidate.limitPrice(), composedAt,
                    "candidate-limit-price:v1:" + candidate.candidateId());
        }
        return jdbc.sql(LATEST_MARK)
                .param("instrumentId", candidate.instrumentId())
                .param("observedBefore", OffsetDateTime.ofInstant(composedAt, java.time.ZoneOffset.UTC))
                .query((rs, row) -> new Mark(
                        rs.getBigDecimal("reference_price"),
                        rs.getObject("reference_observed_at", OffsetDateTime.class).toInstant(),
                        rs.getString("reference_market_hash")))
                .optional()
                .orElse(null);
    }

    private List<LotReservationAllocation> openLots(
            CandidateBatch batch, CandidateOrder candidate, BigDecimal wanted) {
        return jdbc.sql(OPEN_FIFO_LOTS)
                .param("botId", batch.botId())
                .param("flowId", candidate.flowId())
                .param("instrumentId", candidate.instrumentId())
                .query((rs, row) -> new LotReservationAllocation(
                        rs.getObject("id", UUID.class),
                        rs.getObject("opened_at", OffsetDateTime.class).toInstant(),
                        rs.getBigDecimal("available_quantity")))
                .list();
    }

    /** The FIFO slice of the open lots that covers exactly the executable quantity. */
    private static List<LotReservationAllocation> takeFifo(
            List<LotReservationAllocation> lots, BigDecimal quantity) {
        List<LotReservationAllocation> taken = new ArrayList<>();
        BigDecimal remaining = quantity;
        for (LotReservationAllocation lot : lots) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal slice = lot.reservedQuantity().min(remaining);
            taken.add(new LotReservationAllocation(lot.lotId(), lot.openedAt(), slice));
            remaining = remaining.subtract(slice);
        }
        if (remaining.signum() > 0) {
            throw new IllegalStateException(
                    "open lots no longer cover the executable quantity; the batch will retry");
        }
        return taken;
    }

    private static BigDecimal rate(int bps) {
        return BigDecimal.valueOf(bps).divide(BPS, 8, RoundingMode.UNNECESSARY);
    }

    private static BigDecimal amount(BigDecimal value) {
        return value.setScale(AMOUNT_SCALE, RoundingMode.UP);
    }

    private record LaunchPins(
            String brokerRulesVersion, String precisionRulesVersion, UUID feePolicyId,
            UUID bufferPolicyId, String currencyCode) {}

    private record Mark(BigDecimal price, Instant observedAt, String marketHash) {}

    /** One candidate's sizing state as the stages refine it. */
    private static final class Sizing {
        private final OrderSide side;
        private final BigDecimal requestedQuantity;
        private String rejectionReason;
        private Mark mark;
        private BigDecimal estimatedCost = BigDecimal.ZERO;
        private BigDecimal executableQuantity;
        private List<LotReservationAllocation> lots = List.of();

        private Sizing(OrderSide side, BigDecimal requestedQuantity) {
            this.side = side;
            this.requestedQuantity = requestedQuantity;
        }

        private void reject(String reason) {
            if (rejectionReason == null) {
                rejectionReason = reason;
            }
        }

        private void price(Mark value) {
            mark = value;
        }

        private void costs(BigDecimal notional, BigDecimal slippage, BigDecimal fee, BigDecimal buffer) {
            estimatedCost = notional.add(slippage).add(fee).add(buffer);
        }

        private void lots(List<LotReservationAllocation> value, BigDecimal executable) {
            lots = value;
            executableQuantity = executable;
        }

        private void executableQuantity(BigDecimal value) {
            executableQuantity = value;
        }

        private OrderSide side() {
            return side;
        }

        private BigDecimal requestedQuantity() {
            return requestedQuantity;
        }

        private String rejectionReason() {
            return rejectionReason;
        }

        private Mark mark() {
            return mark;
        }

        private BigDecimal estimatedCost() {
            return estimatedCost;
        }

        private BigDecimal executableQuantity() {
            return executableQuantity;
        }

        private List<LotReservationAllocation> lots() {
            return lots;
        }
    }
}
