package com.idea2strategy.trading.persistence.stop;

import com.idea2strategy.trading.domain.stop.BotStopSettlement;
import com.idea2strategy.trading.domain.stop.StopCheckpoint;
import com.idea2strategy.trading.domain.stop.StopReason;
import com.idea2strategy.trading.domain.stop.StopStep;
import java.time.Instant;
import java.util.UUID;

public record BotStopSettlementView(
        UUID settlementId, UUID botId, String stopReason, String reasonDetail, String checkpoint,
        long version, Instant requestedAt, Instant updatedAt, String failedStep, String terminalReason) {
    public BotStopSettlement toDomain() {
        return new BotStopSettlement(settlementId, botId, StopReason.valueOf(stopReason), reasonDetail,
                StopCheckpoint.valueOf(checkpoint), version, requestedAt, updatedAt,
                failedStep == null ? null : StopStep.valueOf(failedStep), terminalReason);
    }
}
