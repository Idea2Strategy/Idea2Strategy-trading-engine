package com.idea2strategy.trading.application.candidate;

import java.util.Objects;
import java.util.UUID;

public record CandidateBatchClaim(UUID batchId, UUID token) {
    public CandidateBatchClaim {
        batchId = Objects.requireNonNull(batchId, "batchId");
        token = Objects.requireNonNull(token, "token");
    }
}
