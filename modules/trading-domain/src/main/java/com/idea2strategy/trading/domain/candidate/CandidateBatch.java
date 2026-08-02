package com.idea2strategy.trading.domain.candidate;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A batch of order candidates to process.
 *
 * <p>The partition isolation scope is optional here only because the upstream contract carried none
 * before schema version 2. A batch without it can still be processed, but it can never become a
 * canonical order intent: {@code trading.order_intent_batches} requires {@code bot_id},
 * {@code partition_id} and {@code source_event_id} NOT NULL.
 */
public record CandidateBatch(
        UUID batchId,
        UUID evaluationId,
        UUID botId,
        UUID partitionId,
        UUID sourceEventId,
        Instant createdAt,
        List<CandidateOrder> candidates) {

    public CandidateBatch {
        batchId = Objects.requireNonNull(batchId, "batchId");
        evaluationId = Objects.requireNonNull(evaluationId, "evaluationId");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));

        boolean scoped = botId != null || partitionId != null || sourceEventId != null;
        if (scoped) {
            Objects.requireNonNull(botId, "botId");
            Objects.requireNonNull(partitionId, "partitionId");
            Objects.requireNonNull(sourceEventId, "sourceEventId");
            for (CandidateOrder candidate : candidates) {
                if (candidate.flowId() == null) {
                    throw new IllegalArgumentException(
                            "candidate " + candidate.candidateId() + " has no flowId");
                }
            }
        }
    }

    /** The unscoped shape, kept for batches that arrive on schema version 1. */
    public CandidateBatch(
            UUID batchId, UUID evaluationId, Instant createdAt, List<CandidateOrder> candidates) {
        this(batchId, evaluationId, null, null, null, createdAt, candidates);
    }

    /** True when this batch can become a canonical order intent. */
    public boolean carriesPartitionScope() {
        return botId != null;
    }

    public Optional<UUID> bot() {
        return Optional.ofNullable(botId);
    }

    public Optional<UUID> partition() {
        return Optional.ofNullable(partitionId);
    }

    public Optional<UUID> sourceEvent() {
        return Optional.ofNullable(sourceEventId);
    }
}
