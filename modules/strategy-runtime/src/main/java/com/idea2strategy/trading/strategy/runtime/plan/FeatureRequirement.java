package com.idea2strategy.trading.strategy.runtime.plan;

public record FeatureRequirement(String featureId, String version) {
    public FeatureRequirement {
        featureId = PlanValueValidation.requireText(featureId, "featureId");
        version = PlanValueValidation.requireText(version, "version");
    }
}
