package com.idea2strategy.trading.strategy.runtime.incremental;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record IncrementalFeatureSnapshot(
        UUID botId,
        long triggerSequence,
        String eventId,
        RuntimeTriggerType triggerType,
        Instant occurredAt,
        Map<FeatureKey, IncrementalFeatureState> featureStates) {

    public IncrementalFeatureSnapshot {
        botId = Objects.requireNonNull(botId, "botId");
        if (triggerSequence < 0) {
            throw new IllegalArgumentException("triggerSequence must not be negative");
        }
        eventId = IncrementalValueValidation.requireText(eventId, "eventId");
        triggerType = Objects.requireNonNull(triggerType, "triggerType");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        featureStates = Map.copyOf(Objects.requireNonNull(featureStates, "featureStates"));
    }
}
