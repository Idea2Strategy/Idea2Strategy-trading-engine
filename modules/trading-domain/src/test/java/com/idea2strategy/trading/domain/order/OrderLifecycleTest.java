package com.idea2strategy.trading.domain.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderLifecycleTest {

    private static final Instant T0 = Instant.parse("2029-01-01T09:00:00Z");
    private static final Instant T1 = Instant.parse("2029-01-01T09:01:00Z");
    private static final Instant T2 = Instant.parse("2029-01-01T09:02:00Z");
    private static final Instant T3 = Instant.parse("2029-01-01T09:03:00Z");
    @Test
    void appliesPartialFillsAndAnExactFinalFill() {
        OrderLifecycle accepted = accepted(dayTerms("5"));

        OrderLifecycle partial = accepted.applyFill(new BigDecimal("2"), T1);
        assertEquals(OrderStatus.PARTIALLY_FILLED, partial.status());
        assertEquals(0, partial.cumulativeFilledQuantity().compareTo(new BigDecimal("2")));
        assertEquals(2, partial.version());

        OrderLifecycle repeatedPartial = partial.applyFill(new BigDecimal("1"), T2);
        OrderLifecycle filled = repeatedPartial.applyFill(new BigDecimal("2"), T3);
        assertEquals(OrderStatus.PARTIALLY_FILLED, repeatedPartial.status());
        assertEquals(OrderStatus.FILLED, filled.status());
        assertEquals(0, filled.cumulativeFilledQuantity().compareTo(new BigDecimal("5")));
        assertEquals(4, filled.version());
    }

    @Test
    void rejectsInvalidFillDeltasAndTimestampsWithoutChangingTheAggregate() {
        OrderLifecycle accepted = accepted(dayTerms("5"));
        OrderLifecycle partial = accepted.applyFill(new BigDecimal("2"), T1);

        assertThrows(IllegalArgumentException.class, () -> accepted.applyFill(BigDecimal.ZERO, T1));
        assertThrows(IllegalArgumentException.class, () -> accepted.applyFill(new BigDecimal("-1"), T1));
        assertThrows(IllegalArgumentException.class, () -> partial.applyFill(new BigDecimal("4"), T2));
        assertThrows(IllegalArgumentException.class, () -> partial.applyFill(new BigDecimal("1"), T0));
        assertEquals(0, partial.cumulativeFilledQuantity().compareTo(new BigDecimal("2")));
        assertEquals(2, partial.version());
    }

    @Test
    void cancelsAndExpiresRemainingQuantityAfterAPartialFill() {
        OrderLifecycle partial = accepted(dayTerms("5")).applyFill(new BigDecimal("2"), T1);

        OrderLifecycle cancelled = partial.cancel("USER_REQUEST", T2);
        assertEquals(OrderStatus.CANCELLED, cancelled.status());
        assertEquals("USER_REQUEST", cancelled.terminalReason());
        assertEquals(3, cancelled.version());

        OrderLifecycle expired = partial.expire(T2, T2);
        assertEquals(OrderStatus.EXPIRED, expired.status());
        assertEquals("DAY_SESSION_CLOSE", expired.terminalReason());
        assertEquals(3, expired.version());
    }

    @Test
    void rejectsEveryCommandFromTerminalStatesAndKeepsRejectionInitialOnly() {
        OrderLifecycle filled = accepted(dayTerms("1")).applyFill(BigDecimal.ONE, T1);
        OrderLifecycle cancelled = accepted(dayTerms("1")).cancel("USER_REQUEST", T1);
        OrderLifecycle expired = accepted(dayTerms("1")).expire(T1, T1);
        OrderLifecycle rejected = rejected(dayTerms("1"));

        for (OrderLifecycle terminal : List.of(filled, cancelled, expired, rejected)) {
            assertThrows(IllegalStateException.class, () -> terminal.applyFill(BigDecimal.ONE, T2));
            assertThrows(IllegalStateException.class, () -> terminal.cancel("LATE", T2));
            assertThrows(IllegalStateException.class, () -> terminal.expire(T2, T2));
        }
        assertEquals(OrderStatus.REJECTED, rejected.status());
        assertEquals(1, rejected.version());
        assertEquals("RISK_REJECTED", rejected.terminalReason());
    }

    @Test
    void requiresEligibleAuthoritativeExpirationDeadlines() {
        OrderLifecycle day = accepted(dayTerms("1"));
        OrderLifecycle gtd = accepted(gtdTerms("1", T2));
        OrderLifecycle gtc = accepted(gtcTerms("1"));

        assertThrows(IllegalArgumentException.class, () -> day.expire(T1, null));
        assertThrows(IllegalArgumentException.class, () -> day.expire(T1, T2));
        assertEquals(OrderStatus.EXPIRED, day.expire(T2, T2).status());

        assertThrows(IllegalArgumentException.class, () -> gtd.expire(T1, null));
        assertEquals(OrderStatus.EXPIRED, gtd.expire(T2, null).status());

        assertThrows(IllegalStateException.class, () -> gtc.expire(T2, null));
    }

    @Test
    void validatesReconstructedAggregateState() {
        OrderLifecycle accepted = accepted(dayTerms("5"));

        assertThrows(IllegalArgumentException.class, () -> new OrderLifecycle(
                accepted.orderId(), accepted.createCommandId(), accepted.requestFingerprint(), accepted.terms(),
                OrderStatus.ACCEPTED, new BigDecimal("1"), 1, T0, T0, null));
        assertThrows(IllegalArgumentException.class, () -> new OrderLifecycle(
                accepted.orderId(), accepted.createCommandId(), accepted.requestFingerprint(), accepted.terms(),
                OrderStatus.FILLED, new BigDecimal("4"), 2, T0, T1, null));
        assertThrows(IllegalArgumentException.class, () -> new OrderLifecycle(
                accepted.orderId(), accepted.createCommandId(), accepted.requestFingerprint(), accepted.terms(),
                OrderStatus.CANCELLED, BigDecimal.ZERO, 2, T0, T1, null));
    }

    private static OrderTerms dayTerms(String quantity) {
        return terms(quantity, TimeInForce.DAY, null);
    }

    private static OrderTerms gtcTerms(String quantity) {
        return terms(quantity, TimeInForce.GTC, null);
    }

    private static OrderTerms gtdTerms(String quantity, Instant expiresAt) {
        return terms(quantity, TimeInForce.GTD, expiresAt);
    }

    private static OrderTerms terms(String quantity, TimeInForce timeInForce, Instant expiresAt) {
        return new OrderTerms(
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                UUID.fromString("30000000-0000-0000-0000-000000000003"),
                OrderSide.BUY,
                new BigDecimal(quantity),
                OrderType.MARKET,
                timeInForce,
                null,
                null,
                null,
                expiresAt);
    }

    private static OrderLifecycle accepted(OrderTerms terms) {
        return new OrderLifecycle(
                UUID.fromString("40000000-0000-5000-8000-000000000004"),
                UUID.fromString("50000000-0000-5000-8000-000000000005"),
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                terms,
                OrderStatus.ACCEPTED,
                BigDecimal.ZERO,
                1,
                T0,
                T0,
                null);
    }

    private static OrderLifecycle rejected(OrderTerms terms) {
        return new OrderLifecycle(
                UUID.fromString("40000000-0000-5000-8000-000000000004"),
                UUID.fromString("50000000-0000-5000-8000-000000000005"),
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                terms,
                OrderStatus.REJECTED,
                BigDecimal.ZERO,
                1,
                T0,
                T0,
                "RISK_REJECTED");
    }
}
