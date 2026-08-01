package com.idea2strategy.trading.application.port;

import com.idea2strategy.trading.domain.candidate.CandidateBatch;

public interface CandidateBatchClaimPort {
    boolean claim(CandidateBatch batch);
}
