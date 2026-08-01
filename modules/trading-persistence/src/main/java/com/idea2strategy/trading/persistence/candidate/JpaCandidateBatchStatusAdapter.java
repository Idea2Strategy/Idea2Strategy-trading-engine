package com.idea2strategy.trading.persistence.candidate;

import com.idea2strategy.trading.application.candidate.CandidateBatchClaim;
import com.idea2strategy.trading.application.candidate.CandidateBatchClaimLostException;
import com.idea2strategy.trading.application.port.CandidateBatchStatusPort;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class JpaCandidateBatchStatusAdapter implements CandidateBatchStatusPort {
    private final CandidateBatchProcessingRepository repository;

    public JpaCandidateBatchStatusAdapter(CandidateBatchProcessingRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    @Transactional
    public void complete(CandidateBatchClaim claim) {
        requireActive(repository.complete(claim.batchId(), claim.token()), claim);
    }

    @Override
    @Transactional
    public void fail(CandidateBatchClaim claim, String reason) {
        requireActive(repository.fail(claim.batchId(), claim.token(), reason), claim);
    }

    private static void requireActive(int updatedRows, CandidateBatchClaim claim) {
        if (updatedRows != 1) {
            throw new CandidateBatchClaimLostException(claim);
        }
    }
}
