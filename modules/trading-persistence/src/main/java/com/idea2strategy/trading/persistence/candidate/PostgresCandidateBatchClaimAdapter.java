package com.idea2strategy.trading.persistence.candidate;

import com.idea2strategy.trading.application.candidate.CandidateBatchClaim;
import com.idea2strategy.trading.application.port.CandidateBatchClaimPort;
import com.idea2strategy.trading.domain.candidate.CandidateBatch;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
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
    public Optional<CandidateBatchClaim> claim(CandidateBatch batch) {
        UUID claimToken = UUID.randomUUID();
        int inserted = jdbcClient.sql("""
                        insert into trading.candidate_batch_processing (
                            batch_id, evaluation_id, source_created_at, status,
                            claim_token, lease_expires_at
                        ) values (
                            :batchId, :evaluationId, :sourceCreatedAt, 'PROCESSING',
                            :claimToken, current_timestamp + interval '15 minutes'
                        )
                        on conflict (batch_id) do update
                        set evaluation_id = excluded.evaluation_id,
                            source_created_at = excluded.source_created_at,
                            status = 'PROCESSING',
                            claim_token = excluded.claim_token,
                            lease_expires_at = current_timestamp + interval '15 minutes',
                            failure_reason = null,
                            started_at = current_timestamp,
                            updated_at = current_timestamp
                        where candidate_batch_processing.status = 'FAILED'
                           or (
                               candidate_batch_processing.status = 'PROCESSING'
                               and candidate_batch_processing.lease_expires_at < current_timestamp
                           )
                        """)
                .param("batchId", batch.batchId())
                .param("evaluationId", batch.evaluationId())
                .param("sourceCreatedAt", batch.createdAt().atOffset(ZoneOffset.UTC))
                .param("claimToken", claimToken)
                .update();
        return inserted == 1
                ? Optional.of(new CandidateBatchClaim(batch.batchId(), claimToken))
                : Optional.empty();
    }

    @Override
    public boolean renew(CandidateBatchClaim claim) {
        return jdbcClient.sql("""
                        update trading.candidate_batch_processing
                        set lease_expires_at = current_timestamp + interval '15 minutes',
                            updated_at = current_timestamp
                        where batch_id = :batchId
                          and claim_token = :claimToken
                          and status = 'PROCESSING'
                        """)
                .param("batchId", claim.batchId())
                .param("claimToken", claim.token())
                .update() == 1;
    }
}
