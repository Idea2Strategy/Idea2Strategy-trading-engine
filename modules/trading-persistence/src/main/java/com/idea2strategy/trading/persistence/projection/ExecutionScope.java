package com.idea2strategy.trading.persistence.projection;

import java.util.Objects;
import java.util.UUID;

public record ExecutionScope(UUID botId, UUID partitionId, UUID flowId) {
    public ExecutionScope {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(partitionId, "partitionId");
        Objects.requireNonNull(flowId, "flowId");
    }
}
