package com.idea2strategy.trading.application.order;

import java.time.Instant;
import java.util.UUID;

public record ExpireOrderCommand(
        UUID commandId, UUID orderId, UUID botEventId, long expectedVersion, Instant occurredAt,
        Instant daySessionClose)
        implements OrderLifecycleCommand {

    public ExpireOrderCommand {
        commandId = required(commandId, "commandId");
        orderId = required(orderId, "orderId");
        botEventId = required(botEventId, "botEventId");
        expectedVersion = positiveVersion(expectedVersion);
        occurredAt = required(occurredAt, "occurredAt");
        if (daySessionClose != null && daySessionClose.isAfter(occurredAt)) {
            throw new IllegalArgumentException("daySessionClose must not be after occurredAt");
        }
    }

    private static long positiveVersion(long value) {
        if (value <= 0) {
            throw new IllegalArgumentException("expectedVersion must be positive");
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
