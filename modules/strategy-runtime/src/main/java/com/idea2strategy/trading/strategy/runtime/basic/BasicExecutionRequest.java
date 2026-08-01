package com.idea2strategy.trading.strategy.runtime.basic;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record BasicExecutionRequest(
        UUID evaluationId,
        List<BasicFlow> flows,
        Map<UUID, BasicInstrumentInput> instrumentInputs) {

    public BasicExecutionRequest {
        evaluationId = Objects.requireNonNull(evaluationId, "evaluationId must not be null");
        flows = List.copyOf(Objects.requireNonNull(flows, "flows must not be null"));
        instrumentInputs = Map.copyOf(Objects.requireNonNull(instrumentInputs, "instrumentInputs must not be null"));
        if (flows.isEmpty() || flows.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("flows must be non-empty and non-null");
        }
        if (flows.stream().map(BasicFlow::flowId).distinct().count() != flows.size()) {
            throw new IllegalArgumentException("flow IDs must be unique");
        }
        instrumentInputs.forEach((instrumentId, input) -> {
            if (instrumentId == null || input == null || !instrumentId.equals(input.instrumentId())) {
                throw new IllegalArgumentException("instrument input key must match its instrument ID");
            }
        });
    }
}
