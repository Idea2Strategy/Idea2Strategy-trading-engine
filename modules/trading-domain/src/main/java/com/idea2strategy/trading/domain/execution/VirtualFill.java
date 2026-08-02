package com.idea2strategy.trading.domain.execution;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record VirtualFill(
        UUID fillId,
        BigDecimal quantity,
        BigDecimal referencePrice,
        BigDecimal price,
        BigDecimal notional,
        BigDecimal slippageAmount,
        BigDecimal fee,
        boolean partial,
        Instant occurredAt) {
    public VirtualFill {
        fillId = required(fillId, "fillId");
        quantity = positive(quantity, "quantity");
        referencePrice = positive(referencePrice, "referencePrice");
        price = positive(price, "price");
        notional = positive(notional, "notional");
        slippageAmount = nonNegative(slippageAmount, "slippageAmount");
        fee = nonNegative(fee, "fee");
        occurredAt = required(occurredAt, "occurredAt");
    }

    private static BigDecimal positive(BigDecimal value, String name) {
        BigDecimal normalized = required(value, name).stripTrailingZeros();
        if (normalized.signum() <= 0) throw new IllegalArgumentException(name + " must be positive");
        return normalized;
    }
    private static BigDecimal nonNegative(BigDecimal value, String name) {
        BigDecimal normalized = required(value, name).stripTrailingZeros();
        if (normalized.signum() < 0) throw new IllegalArgumentException(name + " must not be negative");
        return normalized;
    }
    private static <T> T required(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " must not be null");
        return value;
    }
}
