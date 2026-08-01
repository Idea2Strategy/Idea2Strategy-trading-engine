package com.idea2strategy.trading.strategy.runtime.plan;

import java.util.Map;
import java.util.Objects;

public record ExecutionPlanCompatibility(
        String planSchemaVersion,
        String runtimeSchemaVersion,
        Map<String, String> supportedFeatureVersions) {

    public ExecutionPlanCompatibility {
        planSchemaVersion = PlanValueValidation.requireText(planSchemaVersion, "planSchemaVersion");
        runtimeSchemaVersion = PlanValueValidation.requireText(runtimeSchemaVersion, "runtimeSchemaVersion");
        supportedFeatureVersions = Map.copyOf(
                Objects.requireNonNull(supportedFeatureVersions, "supportedFeatureVersions"));
        supportedFeatureVersions.forEach((featureId, version) -> {
            PlanValueValidation.requireText(featureId, "featureId");
            PlanValueValidation.requireText(version, "featureVersion");
        });
    }
}
