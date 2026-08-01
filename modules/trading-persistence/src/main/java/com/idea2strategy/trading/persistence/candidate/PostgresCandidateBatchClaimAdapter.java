package com.idea2strategy.trading.persistence.candidate;

import com.idea2strategy.trading.application.port.CandidateBatchClaimPort;
import com.idea2strategy.trading.domain.candidate.CandidateBatch;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public final class PostgresCandidateBatchClaimAdapter implements CandidateBatchClaimPort {
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
                        on conflict (batch_id) do nothing
                        """)
                .param("batchId", batch.batchId())
                .param("evaluationId", batch.evaluationId())
                .param("sourceCreatedAt", batch.createdAt())
                .update();
        return inserted == 1;
    }
}
