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
        List<Mapping> mappings) {

    public OrderIntentBatchPersistenceView {
        mappings = List.copyOf(mappings);
    }

    public List<OrderIntentIdentity> intents() {
        return mappings.stream()
                .map(Mapping::toDomain)
                .toList();
    }

    public OrderIntentBatch toDomain() {
        for (int index = 0; index < mappings.size(); index++) {
            if (mappings.get(index).ordinal() != index) {
                throw new IllegalArgumentException("stored intent ordinals must be contiguous and zero-based");
            }
        }
        return new OrderIntentBatch(
                batchId,
                botId,
                evaluationId,
                sourceCandidateBatchId,
                requestFingerprint,
                intents());
    }

    public record Mapping(int ordinal, UUID intentId, UUID candidateId) {
        private OrderIntentIdentity toDomain() {
            return new OrderIntentIdentity(intentId, candidateId);
        }
    }
}
