package com.idea2strategy.trading.strategy.runtime.incremental;

public record FeatureKey(String featureId, String version) {
    public FeatureKey {
        featureId = IncrementalValueValidation.requireText(featureId, "featureId");
        version = IncrementalValueValidation.requireText(version, "version");
    }
}
