package com.idea2strategy.trading.domain.intent;

import java.util.List;
import java.util.UUID;

public final class OrderIntentBatchFactory {

    public OrderIntentBatch create(OrderIntentBatchRequest request) {
        OrderIntentIdentityHashing.requireNonNull(request, "request");
        UUID batchId = OrderIntentIdentityHashing.version5(
                OrderIntentIdentityHashing.BATCH_NAMESPACE,
                "order-intent-batch:v1",
                request.botId(),
                request.evaluationId(),
                request.sourceCandidateBatchId(),
                request.candidateIds());
        List<OrderIntentIdentity> intents = request.candidateIds().stream()
                .map(candidateId -> new OrderIntentIdentity(
                        OrderIntentIdentityHashing.version5(
                                OrderIntentIdentityHashing.INTENT_NAMESPACE,
                                "order-intent:v1",
                                batchId,
                                candidateId),
                        candidateId))
                .toList();
        return new OrderIntentBatch(
                batchId,
                request.botId(),
                request.evaluationId(),
                request.sourceCandidateBatchId(),
                OrderIntentIdentityHashing.requestFingerprint(request),
                intents);
    }
}
