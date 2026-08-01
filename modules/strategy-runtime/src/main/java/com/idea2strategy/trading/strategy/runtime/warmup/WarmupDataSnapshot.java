package com.idea2strategy.trading.strategy.runtime.warmup;

import java.util.Map;
import java.util.Objects;

public record WarmupDataSnapshot(
        DatasetManifestSnapshot manifest,
        Map<String, WarmupFeatureSeries> seriesByRequirementId) {

    public WarmupDataSnapshot {
        manifest = Objects.requireNonNull(manifest, "manifest");
        seriesByRequirementId = Map.copyOf(Objects.requireNonNull(seriesByRequirementId, "seriesByRequirementId"));
    }
}
