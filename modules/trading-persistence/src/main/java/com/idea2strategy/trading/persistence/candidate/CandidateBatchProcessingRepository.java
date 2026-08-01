package com.idea2strategy.trading.persistence.candidate;

import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CandidateBatchProcessingRepository
        extends JpaRepository<CandidateBatchProcessingEntity, UUID> {

    @Modifying(clearAutomatically = true)
    @Query(value = """
            update trading.candidate_batch_processing
            set status = 'COMPLETED', failure_reason = null, updated_at = current_timestamp
            where batch_id = :batchId and claim_token = :claimToken and status = 'PROCESSING'
            """, nativeQuery = true)
    int complete(@Param("batchId") UUID batchId, @Param("claimToken") UUID claimToken);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            update trading.candidate_batch_processing
            set status = 'FAILED', failure_reason = :reason, updated_at = current_timestamp
            where batch_id = :batchId and claim_token = :claimToken and status = 'PROCESSING'
            """, nativeQuery = true)
    int fail(
            @Param("batchId") UUID batchId,
            @Param("claimToken") UUID claimToken,
            @Param("reason") String reason);
}
