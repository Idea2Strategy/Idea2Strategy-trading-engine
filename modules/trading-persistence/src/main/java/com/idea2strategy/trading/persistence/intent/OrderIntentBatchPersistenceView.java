package com.idea2strategy.trading.persistence.intent;

import com.idea2strategy.trading.domain.intent.OrderIntentBatch;
import com.idea2strategy.trading.domain.intent.OrderIntentIdentity;
import java.util.List;
import java.util.UUID;

public record OrderIntentBatchPersistenceView(
        UUID batchId,
        UUID botId,
        UUID evaluationId,
        UUID sourceCandidateBatchId,
        String requestFingerprint,
        List<OrderIntentIdentity> intents) {

    public OrderIntentBatchPersistenceView {
        intents = List.copyOf(intents);
    }

    public OrderIntentBatch toDomain() {
        return new OrderIntentBatch(
                batchId,
                botId,
                evaluationId,
                sourceCandidateBatchId,
                requestFingerprint,
                intents);
    }
}
