package com.idea2strategy.trading.messaging.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record OrderCandidateBatch(
        int schemaVersion,
        UUID batchId,
        UUID evaluationId,
        Instant createdAt,
        List<OrderCandidate> candidates) {

    public OrderCandidateBatch {
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        batchId = Objects.requireNonNull(batchId, "batchId");
        evaluationId = Objects.requireNonNull(evaluationId, "evaluationId");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
    }
}
