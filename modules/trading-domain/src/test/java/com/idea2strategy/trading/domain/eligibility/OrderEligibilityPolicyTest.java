package com.idea2strategy.trading.domain.eligibility;

import static com.idea2strategy.trading.domain.eligibility.OrderEligibilityReason.FRACTIONAL_INSTRUMENT_NOT_ENABLED;
import static com.idea2strategy.trading.domain.eligibility.OrderEligibilityReason.FRACTIONAL_REQUIRES_LONG_EXPOSURE;
import static com.idea2strategy.trading.domain.eligibility.OrderEligibilityReason.FRACTIONAL_REQUIRES_MARKET_DAY;
import static com.idea2strategy.trading.domain.eligibility.OrderEligibilityReason.WHOLE_SHARES_REQUIRE_INTEGER_QUANTITY;
import static com.idea2strategy.trading.domain.eligibility.OrderEligibilityStatus.ACCEPTED;
import static com.idea2strategy.trading.domain.eligibility.OrderEligibilityStatus.REJECTED;
import static com.idea2strategy.trading.domain.eligibility.OrderPositionEffect.INCREASE_LONG;
import static com.idea2strategy.trading.domain.eligibility.OrderPositionEffect.INCREASE_SHORT;
import static com.idea2strategy.trading.domain.eligibility.OrderPositionEffect.REDUCE_LONG;
import static com.idea2strategy.trading.domain.eligibility.OrderPositionEffect.REDUCE_SHORT;
import static com.idea2strategy.trading.domain.eligibility.QuantityMode.FRACTIONAL_SHARES;
import static com.idea2strategy.trading.domain.eligibility.QuantityMode.NOTIONAL_AMOUNT;
import static com.idea2strategy.trading.domain.eligibility.QuantityMode.WHOLE_SHARES;
import static com.idea2strategy.trading.domain.order.OrderType.LIMIT;
import static com.idea2strategy.trading.domain.order.OrderType.MARKET;
import static com.idea2strategy.trading.domain.order.OrderType.STOP;
import static com.idea2strategy.trading.domain.order.OrderType.STOP_LIMIT;
import static com.idea2strategy.trading.domain.order.OrderType.TRAILING_STOP;
import static com.idea2strategy.trading.domain.order.TimeInForce.DAY;
import static com.idea2strategy.trading.domain.order.TimeInForce.GTC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class OrderEligibilityPolicyTest {
    private static final UUID INSTRUMENT_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private final OrderEligibilityPolicy policy = new OrderEligibilityPolicy();

    @ParameterizedTest
    @MethodSource("eligibleFractionalOrders")
    void acceptsFractionalAndAmountMarketDayOrdersForEnabledLongExposure(
            QuantityMode mode, OrderPositionEffect effect, String value) {
        BigDecimal exactValue = new BigDecimal(value);
        OrderEligibilityRequest request = request(true, effect, MARKET, DAY, mode, exactValue);

        OrderEligibilityDecision decision = policy.evaluate(request);

        assertEquals(ACCEPTED, decision.status());
        assertEquals(List.of(), decision.reasons());
        assertSame(request, decision.request());
        assertSame(exactValue, decision.request().requestedValue());
        assertEquals(value, decision.request().requestedValue().toPlainString());
    }

    @ParameterizedTest
    @MethodSource("marketDayViolations")
    void rejectsFractionalAndAmountOrdersThatAreNotMarketDay(
            com.idea2strategy.trading.domain.order.OrderType type,
            com.idea2strategy.trading.domain.order.TimeInForce timeInForce) {
        OrderEligibilityDecision decision = policy.evaluate(
                request(true, INCREASE_LONG, type, timeInForce, FRACTIONAL_SHARES, new BigDecimal("0.125")));

        assertEquals(REJECTED, decision.status());
        assertEquals(List.of(FRACTIONAL_REQUIRES_MARKET_DAY), decision.reasons());
    }

    @Test
    void rejectsFractionalAndAmountOrdersWhenInstrumentIsNotEnabled() {
        OrderEligibilityDecision decision = policy.evaluate(
                request(false, INCREASE_LONG, MARKET, DAY, NOTIONAL_AMOUNT, new BigDecimal("25.50")));

        assertEquals(REJECTED, decision.status());
        assertEquals(List.of(FRACTIONAL_INSTRUMENT_NOT_ENABLED), decision.reasons());
    }

    @ParameterizedTest
    @MethodSource("ineligibleFractionalExposure")
    void rejectsFractionalOpeningOrIncreasingShortAndShortReduction(OrderPositionEffect effect) {
        OrderEligibilityDecision decision = policy.evaluate(
                request(true, effect, MARKET, DAY, FRACTIONAL_SHARES, new BigDecimal("0.5")));

        assertEquals(REJECTED, decision.status());
        assertEquals(List.of(FRACTIONAL_REQUIRES_LONG_EXPOSURE), decision.reasons());
    }

    @Test
    void reportsAllFractionalRejectionReasonsInStableOrder() {
        OrderEligibilityDecision decision = policy.evaluate(
                request(false, INCREASE_SHORT, LIMIT, GTC, NOTIONAL_AMOUNT, new BigDecimal("100.00")));

        assertEquals(REJECTED, decision.status());
        assertEquals(List.of(
                FRACTIONAL_INSTRUMENT_NOT_ENABLED,
                FRACTIONAL_REQUIRES_LONG_EXPOSURE,
                FRACTIONAL_REQUIRES_MARKET_DAY), decision.reasons());
    }

    @Test
    void acceptsWholeSharesForEveryOrderShapeAndExposureWhenQuantityIsIntegral() {
        for (var type : List.of(MARKET, LIMIT, STOP, STOP_LIMIT, TRAILING_STOP)) {
            for (var effect : OrderPositionEffect.values()) {
                OrderEligibilityDecision decision = policy.evaluate(
                        request(false, effect, type, GTC, WHOLE_SHARES, new BigDecimal("7.000")));
                assertEquals(ACCEPTED, decision.status());
                assertEquals("7.000", decision.request().requestedValue().toPlainString());
            }
        }
    }

    @Test
    void rejectsNonIntegralWholeShareQuantityWithoutRoundingIt() {
        BigDecimal exactQuantity = new BigDecimal("2.0001");
        OrderEligibilityDecision decision = policy.evaluate(
                request(true, INCREASE_SHORT, TRAILING_STOP, GTC, WHOLE_SHARES, exactQuantity));

        assertEquals(REJECTED, decision.status());
        assertEquals(List.of(WHOLE_SHARES_REQUIRE_INTEGER_QUANTITY), decision.reasons());
        assertSame(exactQuantity, decision.request().requestedValue());
        assertEquals("2.0001", decision.request().requestedValue().toPlainString());
    }

    @Test
    void requiresEveryPolicyInputAndPositiveFiniteSupportedDecimal() {
        assertThrows(IllegalArgumentException.class,
                () -> new InstrumentFractionalPolicy(null, true, "alpaca-v1"));
        assertThrows(IllegalArgumentException.class,
                () -> new InstrumentFractionalPolicy(INSTRUMENT_ID, true, " "));
        assertThrows(IllegalArgumentException.class,
                () -> new OrderEligibilityRequest(null, INCREASE_LONG, MARKET, DAY, FRACTIONAL_SHARES, BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class,
                () -> request(true, null, MARKET, DAY, FRACTIONAL_SHARES, BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class,
                () -> request(true, INCREASE_LONG, null, DAY, FRACTIONAL_SHARES, BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class,
                () -> request(true, INCREASE_LONG, MARKET, null, FRACTIONAL_SHARES, BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class,
                () -> request(true, INCREASE_LONG, MARKET, DAY, null, BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class,
                () -> request(true, INCREASE_LONG, MARKET, DAY, FRACTIONAL_SHARES, null));
        assertThrows(IllegalArgumentException.class,
                () -> request(true, INCREASE_LONG, MARKET, DAY, FRACTIONAL_SHARES, BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> request(true, INCREASE_LONG, MARKET, DAY, FRACTIONAL_SHARES, new BigDecimal("1E+1000")));
    }

    private static Stream<Arguments> eligibleFractionalOrders() {
        return Stream.of(
                Arguments.of(FRACTIONAL_SHARES, INCREASE_LONG, "0.1250"),
                Arguments.of(FRACTIONAL_SHARES, REDUCE_LONG, "1.2500"),
                Arguments.of(NOTIONAL_AMOUNT, INCREASE_LONG, "25.50"),
                Arguments.of(NOTIONAL_AMOUNT, REDUCE_LONG, "12.3400"));
    }

    private static Stream<Arguments> marketDayViolations() {
        return Stream.of(
                Arguments.of(LIMIT, DAY),
                Arguments.of(STOP, DAY),
                Arguments.of(STOP_LIMIT, DAY),
                Arguments.of(TRAILING_STOP, DAY),
                Arguments.of(MARKET, GTC),
                Arguments.of(LIMIT, GTC));
    }

    private static Stream<OrderPositionEffect> ineligibleFractionalExposure() {
        return Stream.of(INCREASE_SHORT, REDUCE_SHORT);
    }

    private static OrderEligibilityRequest request(
            boolean fractionalEnabled,
            OrderPositionEffect effect,
            com.idea2strategy.trading.domain.order.OrderType type,
            com.idea2strategy.trading.domain.order.TimeInForce timeInForce,
            QuantityMode mode,
            BigDecimal value) {
        return new OrderEligibilityRequest(
                new InstrumentFractionalPolicy(INSTRUMENT_ID, fractionalEnabled, "alpaca-v1"),
                effect,
                type,
                timeInForce,
                mode,
                value);
    }
}
