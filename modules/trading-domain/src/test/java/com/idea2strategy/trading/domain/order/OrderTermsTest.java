package com.idea2strategy.trading.domain.order;

import static com.idea2strategy.trading.domain.order.OrderSide.BUY;
import static com.idea2strategy.trading.domain.order.OrderType.LIMIT;
import static com.idea2strategy.trading.domain.order.OrderType.MARKET;
import static com.idea2strategy.trading.domain.order.OrderType.STOP;
import static com.idea2strategy.trading.domain.order.OrderType.STOP_LIMIT;
import static com.idea2strategy.trading.domain.order.OrderType.TRAILING_STOP;
import static com.idea2strategy.trading.domain.order.TimeInForce.DAY;
import static com.idea2strategy.trading.domain.order.TimeInForce.GTC;
import static com.idea2strategy.trading.domain.order.TimeInForce.GTD;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class OrderTermsTest {

    private static final BigDecimal TEN = new BigDecimal("10");
    private static final Instant EXPIRY = Instant.parse("2030-01-01T00:00:00Z");

    @ParameterizedTest
    @MethodSource("validTypeShapes")
    void acceptsEveryValidOrderTypeShape(
            OrderType type, BigDecimal limitPrice, BigDecimal stopPrice, BigDecimal trailPercent) {
        assertDoesNotThrow(() -> terms(type, DAY, limitPrice, stopPrice, trailPercent, null));
    }

    @ParameterizedTest
    @MethodSource("validTimeInForceShapes")
    void acceptsEveryValidTimeInForceShape(TimeInForce timeInForce, Instant expiresAt) {
        assertDoesNotThrow(() -> terms(MARKET, timeInForce, null, null, null, expiresAt));
    }

    @Test
    void rejectsMissingAndExtraTypeFields() {
        assertDoesNotThrow(() -> terms(MARKET, DAY, null, null, null, null));
        assertInvalid(() -> terms(MARKET, DAY, TEN, null, null, null), "MARKET");
        assertInvalid(() -> terms(LIMIT, DAY, null, null, null, null), "limitPrice");
        assertInvalid(() -> terms(STOP, DAY, null, null, null, null), "stopPrice");
        assertInvalid(() -> terms(STOP_LIMIT, DAY, TEN, null, null, null), "stopPrice");
        assertInvalid(() -> terms(TRAILING_STOP, GTC, null, null, new BigDecimal("1.01"), null), "trailPercent");
        assertInvalid(() -> terms(LIMIT, DAY, TEN, null, new BigDecimal("0.1"), null), "LIMIT");
        assertInvalid(() -> terms(STOP, DAY, null, TEN, new BigDecimal("0.1"), null), "STOP");
    }

    @Test
    void rejectsMissingAndExtraTimeInForceFields() {
        assertInvalid(() -> terms(MARKET, GTD, null, null, null, null), "expiresAt");
        assertInvalid(() -> terms(MARKET, DAY, null, null, null, EXPIRY), "DAY");
        assertInvalid(() -> terms(MARKET, GTC, null, null, null, EXPIRY), "GTC");
    }

    @Test
    void normalizesDecimalsAndRejectsNonPositiveValues() {
        OrderTerms orderTerms = terms(LIMIT, DAY, new BigDecimal("10.00"), null, null, null);

        assertEquals(new BigDecimal("1E+1"), orderTerms.limitPrice());
        assertEquals(new BigDecimal("5"), orderTerms.quantity());
        assertInvalid(() -> new OrderTerms(intentId(), candidateId(), instrumentId(), BUY,
                BigDecimal.ZERO, MARKET, DAY, null, null, null, null), "quantity");
        assertInvalid(() -> terms(LIMIT, DAY, BigDecimal.ZERO, null, null, null), "limitPrice");
        assertInvalid(() -> terms(TRAILING_STOP, DAY, null, null, BigDecimal.ZERO, null), "trailPercent");
    }

    private static Stream<Arguments> validTypeShapes() {
        return Stream.of(
                Arguments.of(MARKET, null, null, null),
                Arguments.of(LIMIT, TEN, null, null),
                Arguments.of(STOP, null, TEN, null),
                Arguments.of(STOP_LIMIT, TEN, TEN, null),
                Arguments.of(TRAILING_STOP, null, null, new BigDecimal("0.25")));
    }

    private static Stream<Arguments> validTimeInForceShapes() {
        return Stream.of(
                Arguments.of(DAY, null),
                Arguments.of(GTC, null),
                Arguments.of(GTD, EXPIRY));
    }

    private static OrderTerms terms(
            OrderType type, TimeInForce timeInForce, BigDecimal limitPrice,
            BigDecimal stopPrice, BigDecimal trailPercent, Instant expiresAt) {
        return new OrderTerms(intentId(), candidateId(), instrumentId(), BUY, new BigDecimal("5.00"),
                type, timeInForce, limitPrice, stopPrice, trailPercent, expiresAt);
    }

    private static void assertInvalid(Runnable construction, String messagePart) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, construction::run);
        assertTrue(exception.getMessage().contains(messagePart));
    }

    private static UUID intentId() {
        return UUID.fromString("10000000-0000-0000-0000-000000000001");
    }

    private static UUID candidateId() {
        return UUID.fromString("20000000-0000-0000-0000-000000000002");
    }

    private static UUID instrumentId() {
        return UUID.fromString("30000000-0000-0000-0000-000000000003");
    }
}
