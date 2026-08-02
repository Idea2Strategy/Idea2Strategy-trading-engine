package com.idea2strategy.trading.application.stop;

import com.idea2strategy.trading.domain.stop.StopReason;
import java.time.Instant;
import java.util.UUID;

public record RequestBotStopCommand(UUID botId, StopReason reason, String detail, Instant requestedAt) {
    public RequestBotStopCommand {
        if (botId == null || reason == null || requestedAt == null) {
            throw new IllegalArgumentException("botId, reason and requestedAt are required");
        }
        if (detail == null || detail.isBlank()) {
            throw new IllegalArgumentException("detail must not be blank");
        }
    }
}
