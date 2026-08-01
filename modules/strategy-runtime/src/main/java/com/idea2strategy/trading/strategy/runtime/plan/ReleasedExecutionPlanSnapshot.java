package com.idea2strategy.trading.strategy.runtime.plan;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record ReleasedExecutionPlanSnapshot(
        UUID botId,
        UUID releaseId,
        boolean locked,
        String planSchemaVersion,
        Set<FeatureRequirement> requiredFeatures,
        String planPayload,
        String integritySha256) {

    public ReleasedExecutionPlanSnapshot {
        botId = Objects.requireNonNull(botId, "botId");
        releaseId = Objects.requireNonNull(releaseId, "releaseId");
        planSchemaVersion = PlanValueValidation.requireText(planSchemaVersion, "planSchemaVersion");
        requiredFeatures = Set.copyOf(Objects.requireNonNull(requiredFeatures, "requiredFeatures"));
        if (requiredFeatures.size() != requiredFeatures.stream().map(FeatureRequirement::featureId).distinct().count()) {
            throw new IllegalArgumentException("requiredFeatures must not contain duplicate feature IDs");
        }
        planPayload = PlanValueValidation.requireText(planPayload, "planPayload");
        integritySha256 = PlanValueValidation.requireSha256(integritySha256, "integritySha256");
    }

    public static ReleasedExecutionPlanSnapshot locked(
            UUID botId,
            UUID releaseId,
            String planSchemaVersion,
            Set<FeatureRequirement> requiredFeatures,
            String planPayload) {
        return new ReleasedExecutionPlanSnapshot(
                botId,
                releaseId,
                true,
                planSchemaVersion,
                requiredFeatures,
                planPayload,
                ExecutionPlanIntegrity.planSha256(
                        botId, releaseId, true, planSchemaVersion, requiredFeatures, planPayload));
    }
}
