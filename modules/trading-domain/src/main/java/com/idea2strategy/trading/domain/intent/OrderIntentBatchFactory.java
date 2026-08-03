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
                OrderIntentOrigin.FLOW_EVALUATION,
                request.evaluationId(),
                OrderIntentIdentityHashing.inputStateHash(request),
                OrderIntentIdentityHashing.conflictPolicyHash(COMPOSITION_RULES_VERSION),
                COMPOSITION_RULES_VERSION,
                OrderIntentIdentityHashing.resultHash(intents),
                request.composedAt(),
                intents);
    }

    /**
     * The intents a bot stop settlement generates to flatten what the bot still holds.
     *
     * <p>No evaluation produced these, so identity cannot come from an evaluation id. It comes from
     * the settlement's own official event instead: the same {@code sourceEventId} — which the
     * liquidation step derives from its operation — recomposes to the same batch, the same intents
     * and therefore the same {@code system_close_actions}, which is what makes a settlement resumed
     * after a crash converge instead of double-selling.
     */
    public OrderIntentBatch createStopLiquidation(
            UUID botId,
            UUID partitionId,
            UUID sourceEventId,
            java.time.Instant composedAt,
            List<OrderIntentRequest> requests) {
        OrderIntentIdentityHashing.requireNonNull(sourceEventId, "sourceEventId");
        UUID batchId = OrderIntentIdentityHashing.version5(
                OrderIntentIdentityHashing.BATCH_NAMESPACE,
                "stop-liquidation-batch:v1",
                sourceEventId);
        List<OrderIntent> intents = requests.stream()
                .map(intent -> new OrderIntent(
                        OrderIntentIdentityHashing.version5(
                                OrderIntentIdentityHashing.INTENT_NAMESPACE,
                                "stop-liquidation-intent:v1",
                                sourceEventId,
                                intent.candidateId()),
                        OrderIntent.INTENT_KEY_PREFIX + intent.candidateId(),
                        intent))
                .toList();
        return new OrderIntentBatch(
                batchId,
                botId,
                partitionId,
                sourceEventId,
                OrderIntentOrigin.SYSTEM_STOP_LIQUIDATION,
                null,
                OrderIntentIdentityHashing.stopLiquidationInputHash(
                        botId, partitionId, sourceEventId, composedAt, requests),
                OrderIntentIdentityHashing.conflictPolicyHash(COMPOSITION_RULES_VERSION),
                COMPOSITION_RULES_VERSION,
                OrderIntentIdentityHashing.resultHash(intents),
                composedAt,
                intents);
    }
}
