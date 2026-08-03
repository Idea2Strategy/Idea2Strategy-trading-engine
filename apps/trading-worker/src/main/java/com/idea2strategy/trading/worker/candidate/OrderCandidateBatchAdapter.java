package com.idea2strategy.trading.worker.candidate;

import com.idea2strategy.trading.domain.candidate.CandidateAllocation;
import com.idea2strategy.trading.domain.candidate.CandidateBatch;
import com.idea2strategy.trading.domain.candidate.CandidateOrder;
import com.idea2strategy.trading.messaging.evaluation.OrderCandidate;
import com.idea2strategy.trading.messaging.evaluation.OrderCandidateBatch;
import com.idea2strategy.trading.messaging.evaluation.OrderSide;
import org.springframework.stereotype.Component;

/**
 * Maps the producer owned candidate batch onto this service's domain.
 *
 * <p>Schema version 2 carries the partition isolation scope that a canonical order intent needs, so
 * both versions are accepted and the scope is passed through when present. Version 1 still flows
 * because the existing producer has not moved yet; it simply cannot reach the canonical intent
 * write, which checks {@link CandidateBatch#carriesPartitionScope()}.
 *
 * <p>Version 3 moves sizing here: a buy carries an allocation share and a sell carries no measure at
 * all, because only this service holds the spendable cash, the reference price, the fee and the
 * buffer, and only it knows what a position holds. The per-version shape is checked here rather than
 * on the record, because this is the only place the schema version and the candidate are both in
 * hand — a version 2 candidate without a quantity is malformed, and a version 3 buy without a share
 * is too, but neither statement can be made by a record that has to accept both shapes.
 */
@Component
public final class OrderCandidateBatchAdapter {

    private static final int MINIMUM_SCHEMA_VERSION = 1;

    public CandidateBatch toDomain(OrderCandidateBatch source) {
        int version = source.schemaVersion();
        if (version < MINIMUM_SCHEMA_VERSION || version > OrderCandidateBatch.ALLOCATION_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported order candidate batch schema version: " + version);
        }
        source.candidates().forEach(candidate -> requireShapeOfVersion(version, candidate));
        return new CandidateBatch(
                source.batchId(),
                source.evaluationId(),
                source.botId(),
                source.partitionId(),
                source.sourceEventId(),
                source.createdAt(),
                source.candidates().stream().map(this::toDomain).toList());
    }

    /**
     * A candidate has to carry the measure its schema version promises.
     *
     * <p>Refused here rather than defaulted, because both silent defaults are dangerous: reading a
     * missing version 2 quantity as zero would drop a trade the producer asked for, and reading a
     * missing version 3 share as "all of it" would spend the whole partition on one instrument.
     */
    private static void requireShapeOfVersion(int version, OrderCandidate candidate) {
        if (version < OrderCandidateBatch.ALLOCATION_SCHEMA_VERSION) {
            if (candidate.requestedQuantity().isEmpty()) {
                throw new IllegalArgumentException(
                        "schema version " + version + " candidate " + candidate.candidateId()
                                + " must carry a quantity");
            }
            if (candidate.carriesAllocation()) {
                throw new IllegalArgumentException(
                        "schema version " + version + " candidate " + candidate.candidateId()
                                + " must not carry an allocation share");
            }
            return;
        }
        if (candidate.requestedQuantity().isPresent()) {
            throw new IllegalArgumentException(
                    "schema version " + version + " candidate " + candidate.candidateId()
                            + " is sized by this service and must not carry a quantity");
        }
        if (candidate.side() == OrderSide.BUY && !candidate.carriesAllocation()) {
            throw new IllegalArgumentException(
                    "schema version " + version + " BUY candidate " + candidate.candidateId()
                            + " must carry an allocation share");
        }
    }

    private CandidateOrder toDomain(OrderCandidate source) {
        return new CandidateOrder(
                source.candidateId(),
                source.instrumentId(),
                source.flowId(),
                source.side().name(),
                source.quantity(),
                source.allocation()
                        .map(share -> new CandidateAllocation(share.numerator(), share.denominator()))
                        .orElse(null),
                source.limitPrice(),
                source.reasonCodes());
    }
}
