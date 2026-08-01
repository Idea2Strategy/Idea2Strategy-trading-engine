package com.idea2strategy.trading.strategy.runtime.judgment;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record JudgmentSubject(
        UUID evaluationId,
        Optional<String> flowId,
        Optional<UUID> instrumentId,
        Optional<UUID> candidateId) {

    public JudgmentSubject {
        evaluationId = Objects.requireNonNull(evaluationId, "evaluationId must not be null");
        flowId = Objects.requireNonNull(flowId, "flowId must not be null")
                .map(value -> JudgmentValueValidation.requireText(value, "flowId"));
        instrumentId = Objects.requireNonNull(instrumentId, "instrumentId must not be null")
                .map(value -> Objects.requireNonNull(value, "instrumentId value must not be null"));
        candidateId = Objects.requireNonNull(candidateId, "candidateId must not be null")
                .map(value -> Objects.requireNonNull(value, "candidateId value must not be null"));
    }
}
