package com.idea2strategy.trading.strategy.runtime.warmup;

import java.util.List;
import java.util.Objects;

public record WarmupFeatureSeries(
        String requirementId,
        String featureId,
        String featureVersion,
        String resolution,
        String manifestId,
        String datasetHash,
        List<FeatureObservation> observations) {

    public WarmupFeatureSeries {
        requirementId = WarmupValueValidation.requireText(requirementId, "requirementId");
        featureId = WarmupValueValidation.requireText(featureId, "featureId");
        featureVersion = WarmupValueValidation.requireText(featureVersion, "featureVersion");
        resolution = WarmupValueValidation.requireText(resolution, "resolution");
        manifestId = WarmupValueValidation.requireText(manifestId, "manifestId");
        datasetHash = WarmupValueValidation.requireSha256(datasetHash, "datasetHash");
        observations = List.copyOf(Objects.requireNonNull(observations, "observations"));
    }
}
