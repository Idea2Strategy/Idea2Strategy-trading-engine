package com.idea2strategy.trading.domain.reservation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LotReservationAllocation(
        UUID lotId,
        Instant openedAt,
        BigDecimal reserved,
        BigDecimal consumed,
        BigDecimal released) {

    public LotReservationAllocation {
        lotId = required(lotId, "lotId");
        openedAt = required(openedAt, "openedAt");
        reserved = positive(reserved, "reserved");
        consumed = nonNegative(consumed, "consumed");
        released = nonNegative(released, "released");
        if (consumed.add(released).compareTo(reserved) > 0) {
            throw new IllegalArgumentException("lot consumed and released quantity exceeds reserved quantity");
        }
    }

    public BigDecimal remaining() {
        return reserved.subtract(consumed).subtract(released).stripTrailingZeros();
    }

    public LotReservationAllocation consume(BigDecimal quantity) {
        BigDecimal value = positive(quantity, "quantity");
        if (value.compareTo(remaining()) > 0) {
            throw new IllegalArgumentException("lot consumption exceeds remaining quantity");
        }
        return new LotReservationAllocation(lotId, openedAt, reserved, consumed.add(value), released);
    }

    public LotReservationAllocation releaseRemaining() {
        return new LotReservationAllocation(lotId, openedAt, reserved, consumed, released.add(remaining()));
    }

    private static BigDecimal positive(BigDecimal value, String name) {
        BigDecimal normalized = nonNegative(value, name);
        if (normalized.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return normalized;
    }

    private static BigDecimal nonNegative(BigDecimal value, String name) {
        BigDecimal normalized = required(value, name).stripTrailingZeros();
        if (normalized.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return normalized;
    }

    private static <T> T required(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }
}
