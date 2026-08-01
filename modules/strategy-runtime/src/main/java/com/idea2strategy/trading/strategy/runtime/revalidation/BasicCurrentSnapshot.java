package com.idea2strategy.trading.strategy.runtime.revalidation;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

public record BasicCurrentSnapshot(
        Map<UUID, String> marketVersions,
        Map<UUID, String> positionVersions,
        Optional<String> budgetVersion,
        OptionalLong runtimeStateVersion,
        BasicBotRuntimeStatus runtimeStatus) {

    public BasicCurrentSnapshot {
        marketVersions = RevalidationValueValidation.immutableVersions(marketVersions, "marketVersions");
        positionVersions = RevalidationValueValidation.immutableVersions(positionVersions, "positionVersions");
        budgetVersion = Objects.requireNonNull(budgetVersion, "budgetVersion must not be null")
                .map(version -> RevalidationValueValidation.requireText(version, "budgetVersion"));
        runtimeStateVersion = Objects.requireNonNull(
                runtimeStateVersion, "runtimeStateVersion must not be null");
        if (runtimeStateVersion.isPresent() && runtimeStateVersion.getAsLong() < 0) {
            throw new IllegalArgumentException("runtimeStateVersion must not be negative");
        }
        runtimeStatus = Objects.requireNonNull(runtimeStatus, "runtimeStatus must not be null");
    }
}
