package com.idea2strategy.trading.persistence.candidate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "trading", name = "candidate_batch_processing")
public class CandidateBatchProcessingEntity {
    @Id
    @Column(name = "batch_id", nullable = false, updatable = false)
    private UUID batchId;

    @Column(name = "evaluation_id", nullable = false, updatable = false)
    private UUID evaluationId;

    @Column(name = "source_created_at", nullable = false, updatable = false)
    private Instant sourceCreatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private CandidateBatchProcessingStatus status;

    @Column(name = "failure_reason", length = 512)
    private String failureReason;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CandidateBatchProcessingEntity() {
    }

    public UUID getBatchId() {
        return batchId;
    }

    public CandidateBatchProcessingStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void complete(Instant completedAt) {
        status = CandidateBatchProcessingStatus.COMPLETED;
        failureReason = null;
        updatedAt = completedAt;
    }

    public void fail(String reason, Instant failedAt) {
        status = CandidateBatchProcessingStatus.FAILED;
        failureReason = reason;
        updatedAt = failedAt;
    }
}
