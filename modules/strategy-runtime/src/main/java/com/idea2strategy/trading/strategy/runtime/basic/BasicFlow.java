package com.idea2strategy.trading.strategy.runtime.basic;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record BasicFlow(
        String flowId,
        BasicOrderSide side,
        List<UUID> instrumentIds,
        List<BasicConditionStep> conditionSteps) {

    public BasicFlow {
        flowId = BasicValueValidation.requireText(flowId, "flowId");
        side = Objects.requireNonNull(side, "side must not be null");
        instrumentIds = List.copyOf(Objects.requireNonNull(instrumentIds, "instrumentIds must not be null"));
        conditionSteps = List.copyOf(Objects.requireNonNull(conditionSteps, "conditionSteps must not be null"));
        if (instrumentIds.isEmpty() || instrumentIds.stream().anyMatch(Objects::isNull)
                || new HashSet<>(instrumentIds).size() != instrumentIds.size()) {
            throw new IllegalArgumentException("instrumentIds must be non-empty, unique, and non-null");
        }
        if (conditionSteps.isEmpty() || conditionSteps.stream().anyMatch(Objects::isNull)
                || conditionSteps.stream().map(BasicConditionStep::stepId).distinct().count() != conditionSteps.size()) {
            throw new IllegalArgumentException("conditionSteps must be non-empty, unique, and non-null");
        }
    }
}
