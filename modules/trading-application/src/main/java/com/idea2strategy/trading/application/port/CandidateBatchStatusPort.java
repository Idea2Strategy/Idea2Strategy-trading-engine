package com.idea2strategy.trading.application.port;

import java.util.UUID;

public interface CandidateBatchStatusPort {
    void complete(UUID batchId);

    void fail(UUID batchId, String reason);
}
