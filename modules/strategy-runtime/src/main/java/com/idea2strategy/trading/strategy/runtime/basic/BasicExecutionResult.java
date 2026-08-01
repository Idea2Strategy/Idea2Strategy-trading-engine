package com.idea2strategy.trading.strategy.runtime.basic;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record BasicExecutionResult(UUID evaluationId, List<BasicInstrumentDecision> decisions) {
    public BasicExecutionResult {
        evaluationId = Objects.requireNonNull(evaluationId, "evaluationId must not be null");
        decisions = List.copyOf(Objects.requireNonNull(decisions, "decisions must not be null"));
    }
}
