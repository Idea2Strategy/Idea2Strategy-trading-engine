package com.idea2strategy.trading.application.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record FillOrderCommand(
        UUID commandId, UUID orderId, UUID botEventId, long expectedVersion, BigDecimal delta,
        Instant occurredAt)
        implements OrderLifecycleCommand {

    public FillOrderCommand {
        commandId = required(commandId, "commandId");
        orderId = required(orderId, "orderId");
        botEventId = required(botEventId, "botEventId");
        expectedVersion = positiveVersion(expectedVersion);
        delta = positive(delta, "delta");
        occurredAt = required(occurredAt, "occurredAt");
    }

    private static long positiveVersion(long value) {
        if (value <= 0) {
            throw new IllegalArgumentException("expectedVersion must be positive");
        }
        return value;
    }

    private static BigDecimal positive(BigDecimal value, String name) {
        BigDecimal normalized = required(value, name).stripTrailingZeros();
        if (normalized.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
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
