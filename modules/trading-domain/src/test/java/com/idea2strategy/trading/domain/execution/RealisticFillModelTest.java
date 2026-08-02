package com.idea2strategy.trading.domain.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idea2strategy.trading.domain.order.OrderLifecycle;
import com.idea2strategy.trading.domain.order.OrderLifecycleFactory;
import com.idea2strategy.trading.domain.order.OrderSide;
import com.idea2strategy.trading.domain.order.OrderTerms;
import com.idea2strategy.trading.domain.order.OrderType;
import com.idea2strategy.trading.domain.order.TimeInForce;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RealisticFillModelTest {
    private static final UUID SNAPSHOT_ID = UUID.fromString("70000000-0000-0000-0000-000000000001");
    private static final UUID INSTRUMENT_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final Instant T0 = Instant.parse("2026-08-02T14:30:00Z");
    private final RealisticFillModel model = new RealisticFillModel();

    @Test
    void marketBuyUsesAskFiniteLiquidityAndFixedAdverseCosts() {
        FillDecision decision = model.evaluate(order(OrderSide.BUY, OrderType.MARKET, "10", null, null, null), quote(
                "99", "8", "100", "3", "100", "12"));

        assertTrue(decision.fill().isPresent());
        VirtualFill fill = decision.fill().orElseThrow();
        assertDecimal("3", fill.quantity());
        assertDecimal("100.05", fill.price());
        assertDecimal("300.15", fill.notional());
        assertDecimal("0.6003", fill.fee());
        assertDecimal("0.15", fill.slippageAmount());
        assertTrue(fill.partial());
        assertEquals(FillEligibility.ELIGIBLE, decision.eligibility());
    }

    @Test
    void sellSlippageIsAlwaysAdverseAndNeverInventsFavorablePrice() {
        VirtualFill fill = model.evaluate(order(OrderSide.SELL, OrderType.MARKET, "2", null, null, null), quote(
                "100", "4", "101", "4", "100.5", "4")).fill().orElseThrow();

        assertDecimal("99.95", fill.price());
        assertTrue(fill.price().compareTo(new BigDecimal("100")) <= 0);
    }

    @Test
    void limitAndStopLimitRespectLimitsAfterTriggering() {
        FillDecision buyLimit = model.evaluate(order(OrderSide.BUY, OrderType.LIMIT, "2", "100.02", null, null), quote(
                "99", "5", "100", "5", "100", "5"));
        assertDecimal("100.02", buyLimit.fill().orElseThrow().price());

        FillDecision rejected = model.evaluate(order(OrderSide.BUY, OrderType.LIMIT, "2", "99.99", null, null), quote(
                "99", "5", "100", "5", "100", "5"));
        assertEquals(FillEligibility.PRICE_LIMIT_NOT_MARKETABLE, rejected.eligibility());
        assertFalse(rejected.fill().isPresent());

        FillDecision stopLimit = model.evaluate(order(OrderSide.BUY, OrderType.STOP_LIMIT, "2", "101", "100.5", null), quote(
                "100", "5", "100.8", "5", "100.6", "5"));
        assertDecimal("100.8504", stopLimit.fill().orElseThrow().price());
    }

    @Test
    void stopAndTrailingStopNeedRecordedTradeTrigger() {
        FillDecision stopWaiting = model.evaluate(order(OrderSide.BUY, OrderType.STOP, "2", null, "101", null), quote(
                "99", "5", "100", "5", "100.9", "5"));
        assertEquals(FillEligibility.TRIGGER_NOT_REACHED, stopWaiting.eligibility());

        FillDecision trailingSell = model.evaluate(
                order(OrderSide.SELL, OrderType.TRAILING_STOP, "2", null, null, "0.05"),
                new RecordedMarketSnapshot(SNAPSHOT_ID, INSTRUMENT_ID, T0,
                        bd("94"), bd("5"), bd("95"), bd("5"), bd("94.9"), bd("5"), bd("100")));
        assertTrue(trailingSell.fill().isPresent());
        assertDecimal("93.953", trailingSell.fill().orElseThrow().price());
    }

    @Test
    void missingOrZeroObservedLiquidityNeverCreatesFill() {
        FillDecision decision = model.evaluate(order(OrderSide.BUY, OrderType.MARKET, "2", null, null, null), quote(
                "99", "5", "100", "0", "100", "5"));
        assertEquals(FillEligibility.NO_OBSERVED_LIQUIDITY, decision.eligibility());
        assertFalse(decision.fill().isPresent());
    }

    @Test
    void identityIsDeterministicAndSnapshotMustMatchInstrument() {
        OrderLifecycle order = order(OrderSide.BUY, OrderType.MARKET, "2", null, null, null);
        RecordedMarketSnapshot snapshot = quote("99", "5", "100", "5", "100", "5");
        FillDecision first = model.evaluate(order, snapshot);
        FillDecision retry = model.evaluate(order, snapshot);
        assertEquals(first.decisionId(), retry.decisionId());
        assertEquals(first.requestFingerprint(), retry.requestFingerprint());

        RecordedMarketSnapshot wrong = new RecordedMarketSnapshot(UUID.randomUUID(), UUID.randomUUID(), T0,
                bd("99"), bd("5"), bd("100"), bd("5"), bd("100"), bd("5"), null);
        assertThrows(IllegalArgumentException.class, () -> model.evaluate(order, wrong));
    }

    private static OrderLifecycle order(OrderSide side, OrderType type, String quantity,
                                        String limit, String stop, String trail) {
        return new OrderLifecycleFactory().accepted(new OrderTerms(
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                UUID.fromString("20000000-0000-0000-0000-000000000001"), INSTRUMENT_ID,
                side, bd(quantity), type, TimeInForce.DAY, bd(limit), bd(stop), bd(trail), null), T0.minusSeconds(60));
    }

    private static RecordedMarketSnapshot quote(String bid, String bidSize, String ask, String askSize,
                                                  String trade, String tradeSize) {
        return new RecordedMarketSnapshot(SNAPSHOT_ID, INSTRUMENT_ID, T0,
                bd(bid), bd(bidSize), bd(ask), bd(askSize), bd(trade), bd(tradeSize), null);
    }

    private static BigDecimal bd(String value) { return value == null ? null : new BigDecimal(value); }
    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
