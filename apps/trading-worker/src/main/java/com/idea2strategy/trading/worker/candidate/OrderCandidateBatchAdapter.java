package com.idea2strategy.trading.worker.candidate;

import com.idea2strategy.trading.domain.candidate.CandidateBatch;
import com.idea2strategy.trading.domain.candidate.CandidateOrder;
import com.idea2strategy.trading.messaging.evaluation.OrderCandidate;
import com.idea2strategy.trading.messaging.evaluation.OrderCandidateBatch;
import org.springframework.stereotype.Component;

@Component
public final class OrderCandidateBatchAdapter {

    public CandidateBatch toDomain(OrderCandidateBatch source) {
        if (source.schemaVersion() != 1) {
            throw new IllegalArgumentException("Unsupported order candidate batch schema version: " + source.schemaVersion());
        }
        return new CandidateBatch(
                source.batchId(),
                source.evaluationId(),
                source.createdAt(),
                source.candidates().stream().map(this::toDomain).toList());
    }

    private CandidateOrder toDomain(OrderCandidate source) {
        return new CandidateOrder(
                source.candidateId(),
                source.instrumentId(),
                source.side().name(),
                source.quantity(),
                source.limitPrice(),
                source.reasonCodes());
    }
}
