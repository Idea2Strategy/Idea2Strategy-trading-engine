package com.idea2strategy.trading.application.port;

import com.idea2strategy.trading.application.candidate.CandidateBatchClaim;
import com.idea2strategy.trading.domain.candidate.CandidateBatch;
import java.util.Optional;

public interface CandidateBatchClaimPort {
    Optional<CandidateBatchClaim> claim(CandidateBatch batch);

    boolean renew(CandidateBatchClaim claim);
}
