package com.idea2strategy.trading.persistence.candidate;

import com.idea2strategy.trading.application.port.CandidateBatchStatusPort;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JpaCandidateBatchStatusAdapter implements CandidateBatchStatusPort {
    private final CandidateBatchProcessingRepository repository;
    private final Clock clock;

    public JpaCandidateBatchStatusAdapter(CandidateBatchProcessingRepository repository) {
        this(repository, Clock.systemUTC());
    }

    JpaCandidateBatchStatusAdapter(CandidateBatchProcessingRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @Transactional
    public void complete(UUID batchId) {
        processing(batchId).complete(Instant.now(clock));
    }

    @Override
    @Transactional
    public void fail(UUID batchId, String reason) {
        processing(batchId).fail(reason, Instant.now(clock));
    }

    private CandidateBatchProcessingEntity processing(UUID batchId) {
        return repository.findById(batchId)
                .orElseThrow(() -> new IllegalStateException("Candidate batch was not claimed: " + batchId));
    }
}
