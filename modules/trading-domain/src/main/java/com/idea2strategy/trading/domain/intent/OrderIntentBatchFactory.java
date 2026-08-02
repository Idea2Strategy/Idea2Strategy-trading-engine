package com.idea2strategy.trading.domain.intent;

import java.util.List;
import java.util.UUID;

/**
 * Derives the stable identity of a canonical order intent batch from its inputs.
 *
 * <p>Both identifiers are version 5 UUIDs over the evaluation, not random values. That is what makes
 * an at-least-once redelivery of the same evaluation resolve to the same canonical rows instead of a
 * second batch, without the store needing a separate deduplication table.
 */
public final class OrderIntentBatchFactory {

    /** Bumped whenever composition or conflict resolution changes what a batch produces. */
    public static final String COMPOSITION_RULES_VERSION = "order-intent-composition:v1";

    public OrderIntentBatch create(OrderIntentBatchRequest request) {
        OrderIntentIdentityHashing.requireNonNull(request, "request");
        UUID batchId = OrderIntentIdentityHashing.version5(
                OrderIntentIdentityHashing.BATCH_NAMESPACE,
                "order-intent-batch:v1",
                request.evaluationId());
        List<OrderIntent> intents = request.intents().stream()
                .map(intent -> new OrderIntent(
                        OrderIntentIdentityHashing.version5(
                                OrderIntentIdentityHashing.INTENT_NAMESPACE,
                                "order-intent:v1",
                                request.evaluationId(),
                                intent.candidateId()),
                        OrderIntent.INTENT_KEY_PREFIX + intent.candidateId(),
                        intent))
                .toList();
        return new OrderIntentBatch(
                batchId,
                request.botId(),
                request.partitionId(),
                request.sourceEventId(),
                request.evaluationId(),
                OrderIntentIdentityHashing.inputStateHash(request),
                OrderIntentIdentityHashing.conflictPolicyHash(COMPOSITION_RULES_VERSION),
                COMPOSITION_RULES_VERSION,
                OrderIntentIdentityHashing.resultHash(intents),
                request.composedAt(),
                intents);
    }
}
