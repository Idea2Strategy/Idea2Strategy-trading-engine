package com.idea2strategy.trading.strategy.runtime.revalidation;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record BasicEvaluationSnapshot(
        UUID evaluationId,
        Map<UUID, String> marketVersions,
        Map<UUID, String> positionVersions,
        String budgetVersion,
        long runtimeStateVersion) {

    public BasicEvaluationSnapshot {
        evaluationId = Objects.requireNonNull(evaluationId, "evaluationId must not be null");
        marketVersions = RevalidationValueValidation.immutableVersions(marketVersions, "marketVersions");
        positionVersions = RevalidationValueValidation.immutableVersions(positionVersions, "positionVersions");
        budgetVersion = RevalidationValueValidation.requireText(budgetVersion, "budgetVersion");
        if (runtimeStateVersion < 0) {
            throw new IllegalArgumentException("runtimeStateVersion must not be negative");
        }
    }
}
