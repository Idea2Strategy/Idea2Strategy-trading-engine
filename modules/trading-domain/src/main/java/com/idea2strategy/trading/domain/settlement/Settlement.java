package com.idea2strategy.trading.domain.settlement;

import java.util.Objects;
import java.util.UUID;

public record Settlement(UUID settlementId, UUID executionId) {
    public Settlement {
        settlementId = Objects.requireNonNull(settlementId, "settlementId");
        executionId = Objects.requireNonNull(executionId, "executionId");
    }
}
