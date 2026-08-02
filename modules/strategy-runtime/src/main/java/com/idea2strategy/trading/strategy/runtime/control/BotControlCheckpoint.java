package com.idea2strategy.trading.strategy.runtime.control;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public record BotControlCheckpoint(
        UUID botId,
        Optional<String> snapshotHash,
        BotControlStatus status,
        long lastAggregateSequence,
        Set<String> processedIdempotencyKeys) {

    public BotControlCheckpoint {
        botId = Objects.requireNonNull(botId, "botId");
        snapshotHash = Objects.requireNonNull(snapshotHash, "snapshotHash");
        status = Objects.requireNonNull(status, "status");
        if (lastAggregateSequence < 0) {
            throw new IllegalArgumentException("lastAggregateSequence must not be negative");
        }
        processedIdempotencyKeys = Set.copyOf(
                Objects.requireNonNull(processedIdempotencyKeys, "processedIdempotencyKeys"));
    }

    static BotControlCheckpoint initial(UUID botId) {
        return new BotControlCheckpoint(botId, Optional.empty(), BotControlStatus.NOT_STARTED, 0, Set.of());
    }

    boolean processed(String idempotencyKey) {
        return processedIdempotencyKeys.contains(idempotencyKey);
    }

    BotControlCheckpoint transition(
            BotControlStatus nextStatus,
            String nextSnapshotHash,
            long aggregateSequence,
            String idempotencyKey) {
        Set<String> processed = new java.util.HashSet<>(processedIdempotencyKeys);
        processed.add(idempotencyKey);
        return new BotControlCheckpoint(
                botId,
                Optional.of(nextSnapshotHash),
                nextStatus,
                Math.max(lastAggregateSequence, aggregateSequence),
                processed);
    }
}
