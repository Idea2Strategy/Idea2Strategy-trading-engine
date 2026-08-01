package com.idea2strategy.trading.application.port;

import com.idea2strategy.trading.application.candidate.CandidateBatchClaim;

public interface CandidateBatchStatusPort {
    void complete(CandidateBatchClaim claim);

    void fail(CandidateBatchClaim claim, String reason);
}
