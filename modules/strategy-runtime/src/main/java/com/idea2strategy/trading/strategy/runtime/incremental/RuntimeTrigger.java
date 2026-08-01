package com.idea2strategy.trading.strategy.runtime.incremental;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

public record RuntimeTrigger(
        UUID botId,
        long sequence,
        String eventId,
        RuntimeTriggerType type,
        Instant occurredAt,
        Map<String, BigDecimal> values) {

    public RuntimeTrigger {
        botId = Objects.requireNonNull(botId, "botId");
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
        eventId = IncrementalValueValidation.requireText(eventId, "eventId");
        type = Objects.requireNonNull(type, "type");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(values, "values");
        TreeMap<String, BigDecimal> copy = new TreeMap<>();
        values.forEach((key, value) -> copy.put(
                IncrementalValueValidation.requireText(key, "value key"),
                Objects.requireNonNull(value, key)));
        values = Map.copyOf(copy);
    }
}
