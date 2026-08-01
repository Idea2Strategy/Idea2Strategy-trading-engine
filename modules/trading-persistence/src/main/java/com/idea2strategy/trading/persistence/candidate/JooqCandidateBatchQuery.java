package com.idea2strategy.trading.persistence.candidate;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
public class JooqCandidateBatchQuery {
    private final DSLContext dsl;

    public JooqCandidateBatchQuery(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    public Optional<CandidateBatchProcessingView> findByBatchId(UUID batchId) {
        return dsl.fetchOptional("""
                        select batch_id, evaluation_id, status, failure_reason
                        from trading.candidate_batch_processing
                        where batch_id = ?
                        """, batchId)
                .map(JooqCandidateBatchQuery::toView);
    }

    public int count() {
        return dsl.fetchCount(dsl.selectFrom("trading.candidate_batch_processing"));
    }

    private static CandidateBatchProcessingView toView(Record record) {
        return new CandidateBatchProcessingView(
                record.get("batch_id", UUID.class),
                record.get("evaluation_id", UUID.class),
                CandidateBatchProcessingStatus.valueOf(record.get("status", String.class)),
                record.get("failure_reason", String.class));
    }
}
