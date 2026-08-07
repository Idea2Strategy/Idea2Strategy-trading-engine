package com.idea2strategy.trading.strategy.runtime.warmup;

import java.util.Map;
import java.util.Objects;

public record PreparedWarmup(
        String manifestId,
        String datasetId,
        long manifestRevision,
        String datasetHash,
        Map<String, WarmupFeatureSeries> seriesByRequirementId) {

    public PreparedWarmup {
        manifestId = WarmupValueValidation.requireText(manifestId, "manifestId");
        datasetId = WarmupValueValidation.requireText(datasetId, "datasetId");
        if (manifestRevision <= 0) {
            throw new IllegalArgumentException("manifestRevision must be positive");
        }
        datasetHash = WarmupValueValidation.requireSha256(datasetHash, "datasetHash");
        seriesByRequirementId = Map.copyOf(Objects.requireNonNull(seriesByRequirementId, "seriesByRequirementId"));
    }

    /** A direct-operation plan needs rolling live bars, but no catalog feature snapshot. */
    public static PreparedWarmup none() {
        return new PreparedWarmup(
                "none", "none", 1, "0".repeat(64), Map.of());
    }
}
