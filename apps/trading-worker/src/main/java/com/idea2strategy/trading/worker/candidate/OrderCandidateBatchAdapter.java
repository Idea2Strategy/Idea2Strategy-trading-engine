package com.idea2strategy.trading.worker.candidate;

import com.idea2strategy.trading.domain.candidate.CandidateBatch;
import com.idea2strategy.trading.domain.candidate.CandidateOrder;
import com.idea2strategy.trading.messaging.evaluation.OrderCandidate;
import com.idea2strategy.trading.messaging.evaluation.OrderCandidateBatch;
import org.springframework.stereotype.Component;

/**
 * Maps the producer owned candidate batch onto this service's domain.
 *
 * <p>Schema version 2 carries the partition isolation scope that a canonical order intent needs, so
 * both versions are accepted and the scope is passed through when present. Version 1 still flows
 * because the existing producer has not moved yet; it simply cannot reach the canonical intent
 * write, which checks {@link CandidateBatch#carriesPartitionScope()}.
 */
@Component
public final class OrderCandidateBatchAdapter {

    private static final int MINIMUM_SCHEMA_VERSION = 1;

    public CandidateBatch toDomain(OrderCandidateBatch source) {
        int version = source.schemaVersion();
        if (version < MINIMUM_SCHEMA_VERSION || version > OrderCandidateBatch.SCOPED_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported order candidate batch schema version: " + version);
        }
        return new CandidateBatch(
                source.batchId(),
                source.evaluationId(),
                source.botId(),
                source.partitionId(),
                source.sourceEventId(),
                source.createdAt(),
                source.candidates().stream().map(this::toDomain).toList());
    }

    private CandidateOrder toDomain(OrderCandidate source) {
        return new CandidateOrder(
                source.candidateId(),
                source.instrumentId(),
                source.flowId(),
                source.side().name(),
                source.quantity(),
                source.limitPrice(),
                source.reasonCodes());
    }
}
