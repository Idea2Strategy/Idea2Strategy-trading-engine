package com.idea2strategy.trading.persistence.projection;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ProjectionReason(
        UUID reasonId,
        ExecutionScope scope,
        UUID orderId,
        ProjectionReasonType type,
        String code,
        String detail,
        Instant occurredAt) {
    public ProjectionReason {
        Objects.requireNonNull(reasonId, "reasonId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(type, "type");
        code = requiredText(code, "code");
        detail = requiredText(detail, "detail");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    private static String requiredText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
