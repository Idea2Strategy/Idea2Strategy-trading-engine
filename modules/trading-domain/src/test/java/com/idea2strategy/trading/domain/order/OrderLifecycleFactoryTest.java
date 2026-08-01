package com.idea2strategy.trading.domain.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderLifecycleFactoryTest {

    private static final Instant CREATED_AT = Instant.parse("2029-01-01T09:00:00Z");
    private final OrderLifecycleFactory factory = new OrderLifecycleFactory();

    @Test
    void createsTheDeterministicIdentityAndFingerprintGoldenVector() {
        OrderLifecycle lifecycle = factory.accepted(goldenTerms(new BigDecimal("5.00"), new BigDecimal("12.340")), CREATED_AT);

        assertEquals(UUID.fromString("225c54e7-1c3b-55ea-9ff8-66aacedf07c2"), lifecycle.orderId());
        assertEquals(UUID.fromString("fcf1f867-2e86-5689-9f1f-04d955fab1a2"), lifecycle.createCommandId());
        assertEquals("03c8927ed5f9387e62e7b66dbf100bcfd03a8364b76542914ae0dfde51824b82",
                lifecycle.requestFingerprint());
        assertEquals(5, lifecycle.orderId().version());
        assertEquals(2, lifecycle.orderId().variant());
        assertEquals(5, lifecycle.createCommandId().version());
        assertEquals(2, lifecycle.createCommandId().variant());
    }

    @Test
    void normalizesDecimalScaleButFingerprintsEveryMeaningfulCreationInput() {
        OrderLifecycle baseline = factory.accepted(goldenTerms(new BigDecimal("5.00"), new BigDecimal("12.340")), CREATED_AT);
        OrderLifecycle scaleEquivalent = factory.accepted(goldenTerms(new BigDecimal("5"), new BigDecimal("12.34")), CREATED_AT);
        OrderLifecycle differentStatus = factory.rejected(goldenTerms(new BigDecimal("5"), new BigDecimal("12.34")), CREATED_AT, "RISK_REJECTED");
        OrderLifecycle differentTime = factory.accepted(goldenTerms(new BigDecimal("5"), new BigDecimal("12.34")), CREATED_AT.plusSeconds(1));
        OrderLifecycle differentPrice = factory.accepted(goldenTerms(new BigDecimal("5"), new BigDecimal("12.35")), CREATED_AT);

        assertEquals(baseline.orderId(), scaleEquivalent.orderId());
        assertEquals(baseline.createCommandId(), scaleEquivalent.createCommandId());
        assertEquals(baseline.requestFingerprint(), scaleEquivalent.requestFingerprint());
        assertNotEquals(baseline.requestFingerprint(), differentStatus.requestFingerprint());
        assertNotEquals(baseline.requestFingerprint(), differentTime.requestFingerprint());
        assertNotEquals(baseline.requestFingerprint(), differentPrice.requestFingerprint());
        assertEquals(OrderStatus.REJECTED, differentStatus.status());
        assertEquals("RISK_REJECTED", differentStatus.terminalReason());
    }

    @Test
    void requiresAValidInitialOutcomeAndGtdExpiryAfterAcceptance() {
        OrderTerms expiryAtAcceptance = new OrderTerms(
                intentId(), candidateId(), instrumentId(), OrderSide.BUY, BigDecimal.ONE,
                OrderType.MARKET, TimeInForce.GTD, null, null, null, CREATED_AT);

        assertThrows(IllegalArgumentException.class, () -> factory.accepted(expiryAtAcceptance, CREATED_AT));
        assertThrows(IllegalArgumentException.class, () -> factory.rejected(goldenTerms(BigDecimal.ONE, BigDecimal.ONE), CREATED_AT, " "));
    }

    private static OrderTerms goldenTerms(BigDecimal quantity, BigDecimal limitPrice) {
        return new OrderTerms(
                intentId(), candidateId(), instrumentId(), OrderSide.BUY, quantity,
                OrderType.LIMIT, TimeInForce.GTD, limitPrice, null, null,
                Instant.parse("2030-01-01T00:00:00Z"));
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
