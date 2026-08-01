package com.idea2strategy.trading.persistence.candidate;

import com.idea2strategy.trading.application.port.CandidateBatchClaimPort;
import com.idea2strategy.trading.domain.candidate.CandidateBatch;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresCandidateBatchClaimAdapter implements CandidateBatchClaimPort {
    private final JdbcClient jdbcClient;

    public PostgresCandidateBatchClaimAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    }

    @Override
    public boolean claim(CandidateBatch batch) {
        int inserted = jdbcClient.sql("""
                        insert into trading.candidate_batch_processing (
                            batch_id, evaluation_id, source_created_at, status
                        ) values (:batchId, :evaluationId, :sourceCreatedAt, 'PROCESSING')
                        on conflict (batch_id) do update
                        set evaluation_id = excluded.evaluation_id,
                            source_created_at = excluded.source_created_at,
                            status = 'PROCESSING',
                            failure_reason = null,
                            started_at = current_timestamp,
                            updated_at = current_timestamp
                        where candidate_batch_processing.status = 'FAILED'
                           or (
                               candidate_batch_processing.status = 'PROCESSING'
                               and candidate_batch_processing.updated_at
                                   < current_timestamp - interval '15 minutes'
                           )
                        """)
                .param("batchId", batch.batchId())
                .param("evaluationId", batch.evaluationId())
                .param("sourceCreatedAt", batch.createdAt().atOffset(ZoneOffset.UTC))
                .update();
        return inserted == 1;
    }
}
