package com.idea2strategy.trading.strategy.runtime.incremental;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record IncrementalRuntimeState(
        UUID botId,
        long lastSequence,
        Map<FeatureKey, IncrementalFeatureState> featureStates) {

    public IncrementalRuntimeState {
        botId = Objects.requireNonNull(botId, "botId");
        if (lastSequence < -1) {
            throw new IllegalArgumentException("lastSequence must be -1 or greater");
        }
        featureStates = Map.copyOf(Objects.requireNonNull(featureStates, "featureStates"));
    }
}
