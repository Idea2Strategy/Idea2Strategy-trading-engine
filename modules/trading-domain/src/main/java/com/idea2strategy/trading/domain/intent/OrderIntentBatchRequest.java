package com.idea2strategy.trading.domain.intent;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * The inputs one evaluation contributes to a canonical order intent batch.
 *
 * <p>{@code partitionId} and {@code sourceEventId} are required because the canonical
 * {@code trading.order_intent_batches} row cannot exist without them: the batch is the trading
 * isolation boundary for exactly one partition of one official bot event. A candidate batch that
 * arrived without partition scope can be processed, but it can never reach this request.
 */
public record OrderIntentBatchRequest(
        UUID botId,
        UUID partitionId,
        UUID sourceEventId,
        UUID evaluationId,
        UUID sourceCandidateBatchId,
        Instant composedAt,
        List<OrderIntentRequest> intents) {

    public OrderIntentBatchRequest {
        OrderIntentIdentityHashing.requireNonNull(botId, "botId");
        OrderIntentIdentityHashing.requireNonNull(partitionId, "partitionId");
        OrderIntentIdentityHashing.requireNonNull(sourceEventId, "sourceEventId");
        OrderIntentIdentityHashing.requireNonNull(evaluationId, "evaluationId");
        OrderIntentIdentityHashing.requireNonNull(sourceCandidateBatchId, "sourceCandidateBatchId");
        OrderIntentIdentityHashing.requireNonNull(composedAt, "composedAt");
        if (composedAt.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException("composedAt must have microsecond precision for PostgreSQL");
        }

        // Sorting here is what makes the batch identity independent of candidate arrival order, so a
        // redelivery that reorders the same work still hashes and stores identically.
        intents = OrderIntentIdentityHashing.immutableList(intents, "intents").stream()
                .sorted(Comparator.comparing(OrderIntentRequest::candidateId))
                .toList();
        List<UUID> candidateIds = intents.stream().map(OrderIntentRequest::candidateId).toList();
        if (candidateIds.size() != new HashSet<>(candidateIds).size()) {
            throw new IllegalArgumentException("intents must not contain duplicate candidateIds");
        }
    }
}
