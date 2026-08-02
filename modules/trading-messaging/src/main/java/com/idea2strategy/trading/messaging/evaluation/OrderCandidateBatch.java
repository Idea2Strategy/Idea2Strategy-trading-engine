package com.idea2strategy.trading.messaging.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A batch of order candidates produced by one evaluation.
 *
 * <p>Schema version 2 adds the partition isolation scope. The canonical
 * {@code trading.order_intent_batches} note calls the batch "한 Bot Event에서 정확히 한 파티션의
 * Flow 의도만 수집하는 거래 격리 경계", and the row requires {@code bot_id}, {@code partition_id}
 * and {@code source_event_id} NOT NULL, the last being a real {@code bot.bot_events} row. None of
 * that can be derived from a version 1 batch, so a version 1 batch still deserialises but cannot
 * produce canonical intents.
 */
public record OrderCandidateBatch(
        int schemaVersion,
        UUID batchId,
        UUID evaluationId,
        UUID botId,
        UUID partitionId,
        UUID sourceEventId,
        Instant createdAt,
        List<OrderCandidate> candidates) {

    /** The first schema version that carries the partition isolation scope. */
    public static final int SCOPED_SCHEMA_VERSION = 2;

    public OrderCandidateBatch {
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        batchId = Objects.requireNonNull(batchId, "batchId");
        evaluationId = Objects.requireNonNull(evaluationId, "evaluationId");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));

        if (schemaVersion >= SCOPED_SCHEMA_VERSION) {
            Objects.requireNonNull(botId, "botId");
            Objects.requireNonNull(partitionId, "partitionId");
            Objects.requireNonNull(sourceEventId, "sourceEventId");
            for (OrderCandidate candidate : candidates) {
                if (candidate.flowId() == null) {
                    throw new IllegalArgumentException(
                            "candidate " + candidate.candidateId() + " has no flowId");
                }
            }
        } else if (botId != null || partitionId != null || sourceEventId != null) {
            // A partial scope is worse than none: it would let a batch look canonical ready while
            // one of the three NOT NULL columns still has nothing to fill it.
            throw new IllegalArgumentException(
                    "schema version " + schemaVersion + " must not carry a partition scope");
        }
    }

    /** The version 1 shape, kept so an existing producer keeps deserialising unchanged. */
    public OrderCandidateBatch(
            int schemaVersion,
            UUID batchId,
            UUID evaluationId,
            Instant createdAt,
            List<OrderCandidate> candidates) {
        this(schemaVersion, batchId, evaluationId, null, null, null, createdAt, candidates);
    }

    /** True when this batch carries everything a canonical order intent needs. */
    public boolean carriesPartitionScope() {
        return schemaVersion >= SCOPED_SCHEMA_VERSION;
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
