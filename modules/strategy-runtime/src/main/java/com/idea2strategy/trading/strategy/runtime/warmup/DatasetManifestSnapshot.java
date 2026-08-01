package com.idea2strategy.trading.strategy.runtime.warmup;

import java.util.List;
import java.util.Objects;

public record DatasetManifestSnapshot(
        String manifestId,
        String datasetId,
        long revision,
        DatasetManifestStatus status,
        String schemaVersion,
        String datasetHash,
        List<DatasetObjectSnapshot> objects) {

    public DatasetManifestSnapshot {
        manifestId = WarmupValueValidation.requireText(manifestId, "manifestId");
        datasetId = WarmupValueValidation.requireText(datasetId, "datasetId");
        if (revision <= 0) {
            throw new IllegalArgumentException("revision must be positive");
        }
        status = Objects.requireNonNull(status, "status");
        schemaVersion = WarmupValueValidation.requireText(schemaVersion, "schemaVersion");
        datasetHash = WarmupValueValidation.requireSha256(datasetHash, "datasetHash");
        objects = List.copyOf(Objects.requireNonNull(objects, "objects"));
        if (objects.isEmpty()) {
            throw new IllegalArgumentException("objects must not be empty");
        }
    }
}
