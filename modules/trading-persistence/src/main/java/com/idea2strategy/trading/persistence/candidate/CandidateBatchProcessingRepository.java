package com.idea2strategy.trading.persistence.candidate;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CandidateBatchProcessingRepository
        extends JpaRepository<CandidateBatchProcessingEntity, UUID> {
}
