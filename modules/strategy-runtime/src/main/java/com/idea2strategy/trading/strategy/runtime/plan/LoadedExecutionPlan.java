package com.idea2strategy.trading.strategy.runtime.plan;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record LoadedExecutionPlan(
        UUID botId,
        UUID releaseId,
        String planSchemaVersion,
        Set<FeatureRequirement> requiredFeatures,
        String planPayload,
        String runtimeSchemaVersion,
        long runtimeSequence,
        Map<String, String> runtimeState) {

    public LoadedExecutionPlan {
        botId = Objects.requireNonNull(botId, "botId");
        releaseId = Objects.requireNonNull(releaseId, "releaseId");
        planSchemaVersion = PlanValueValidation.requireText(planSchemaVersion, "planSchemaVersion");
        requiredFeatures = Set.copyOf(Objects.requireNonNull(requiredFeatures, "requiredFeatures"));
        planPayload = PlanValueValidation.requireText(planPayload, "planPayload");
        runtimeSchemaVersion = PlanValueValidation.requireText(runtimeSchemaVersion, "runtimeSchemaVersion");
        if (runtimeSequence < 0) {
            throw new IllegalArgumentException("runtimeSequence must not be negative");
        }
        runtimeState = Map.copyOf(Objects.requireNonNull(runtimeState, "runtimeState"));
    }
}
