package com.idea2strategy.trading.strategy.runtime.plan;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record RuntimeStateSnapshot(
        UUID botId,
        UUID releaseId,
        String runtimeSchemaVersion,
        long sequence,
        Map<String, String> values,
        String integritySha256) {

    public RuntimeStateSnapshot {
        botId = Objects.requireNonNull(botId, "botId");
        releaseId = Objects.requireNonNull(releaseId, "releaseId");
        runtimeSchemaVersion = PlanValueValidation.requireText(runtimeSchemaVersion, "runtimeSchemaVersion");
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
        values = Map.copyOf(Objects.requireNonNull(values, "values"));
        values.forEach((key, value) -> {
            PlanValueValidation.requireText(key, "runtime state key");
            Objects.requireNonNull(value, "runtime state value");
        });
        integritySha256 = PlanValueValidation.requireSha256(integritySha256, "integritySha256");
    }

    public static RuntimeStateSnapshot signed(
            UUID botId,
            UUID releaseId,
            String runtimeSchemaVersion,
            long sequence,
            Map<String, String> values) {
        return new RuntimeStateSnapshot(
                botId,
                releaseId,
                runtimeSchemaVersion,
                sequence,
                values,
                ExecutionPlanIntegrity.runtimeStateSha256(
                        botId, releaseId, runtimeSchemaVersion, sequence, values));
    }
}
