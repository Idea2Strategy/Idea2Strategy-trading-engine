package com.idea2strategy.trading.persistence.candidate;

import java.util.UUID;

public record CandidateBatchProcessingView(
        UUID batchId,
        UUID evaluationId,
        CandidateBatchProcessingStatus status,
        String failureReason) {
}
