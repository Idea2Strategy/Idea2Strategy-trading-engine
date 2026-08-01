package com.idea2strategy.trading.domain.intent;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public record OrderIntentBatchRequest(
        UUID botId,
        UUID evaluationId,
        UUID sourceCandidateBatchId,
        List<UUID> candidateIds) {

    public OrderIntentBatchRequest {
        OrderIntentIdentityHashing.requireNonNull(botId, "botId");
        OrderIntentIdentityHashing.requireNonNull(evaluationId, "evaluationId");
        OrderIntentIdentityHashing.requireNonNull(sourceCandidateBatchId, "sourceCandidateBatchId");
        candidateIds = OrderIntentIdentityHashing.immutableList(candidateIds, "candidateIds").stream()
                .sorted(Comparator.naturalOrder())
                .toList();
        if (candidateIds.size() != new HashSet<>(candidateIds).size()) {
            throw new IllegalArgumentException("candidateIds must not contain duplicates");
        }
    }
}
