package com.idea2strategy.trading.application.order;

import java.time.Instant;
import java.util.UUID;

public record CancelOrderCommand(
        UUID commandId, UUID orderId, long expectedVersion, String reason, Instant occurredAt)
        implements OrderLifecycleCommand {

    public CancelOrderCommand {
        commandId = required(commandId, "commandId");
        orderId = required(orderId, "orderId");
        expectedVersion = positiveVersion(expectedVersion);
        reason = nonBlank(reason, "reason");
        occurredAt = required(occurredAt, "occurredAt");
    }

    private static long positiveVersion(long value) {
        if (value <= 0) {
            throw new IllegalArgumentException("expectedVersion must be positive");
        }
        return value;
    }

    private static String nonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static <T> T required(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }
}
