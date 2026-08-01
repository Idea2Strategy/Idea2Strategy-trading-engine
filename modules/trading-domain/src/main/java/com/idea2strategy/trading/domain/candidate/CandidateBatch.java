package com.idea2strategy.trading.domain.candidate;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record CandidateBatch(
        UUID batchId,
        UUID evaluationId,
        Instant createdAt,
        List<CandidateOrder> candidates) {

    public CandidateBatch {
        batchId = Objects.requireNonNull(batchId, "batchId");
        evaluationId = Objects.requireNonNull(evaluationId, "evaluationId");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
    }
}
