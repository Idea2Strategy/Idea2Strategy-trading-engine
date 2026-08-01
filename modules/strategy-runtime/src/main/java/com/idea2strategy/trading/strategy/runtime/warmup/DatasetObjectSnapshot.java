package com.idea2strategy.trading.strategy.runtime.warmup;

public record DatasetObjectSnapshot(
        String objectKey,
        String declaredContentSha256,
        String observedContentSha256,
        String schemaVersion) {

    public DatasetObjectSnapshot {
        objectKey = WarmupValueValidation.requireText(objectKey, "objectKey");
        declaredContentSha256 = WarmupValueValidation.requireSha256(
                declaredContentSha256, "declaredContentSha256");
        observedContentSha256 = WarmupValueValidation.requireSha256(
                observedContentSha256, "observedContentSha256");
        schemaVersion = WarmupValueValidation.requireText(schemaVersion, "schemaVersion");
    }
}
