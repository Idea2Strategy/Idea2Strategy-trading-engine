package com.idea2strategy.trading.messaging.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record EvaluationResult(
        int schemaVersion,
        UUID evaluationId,
        UUID botId,
        UUID strategyVersionId,
        String triggeringEventId,
        Instant evaluatedAt,
        EvaluationOutcome outcome,
        List<String> reasonCodes) {

    public EvaluationResult {
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        evaluationId = Objects.requireNonNull(evaluationId, "evaluationId");
        botId = Objects.requireNonNull(botId, "botId");
        strategyVersionId = Objects.requireNonNull(strategyVersionId, "strategyVersionId");
        if (triggeringEventId == null || triggeringEventId.isBlank()) {
            throw new IllegalArgumentException("triggeringEventId must not be blank");
        }
        evaluatedAt = Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        outcome = Objects.requireNonNull(outcome, "outcome");
        reasonCodes = List.copyOf(Objects.requireNonNull(reasonCodes, "reasonCodes"));
    }
}
