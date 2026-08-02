package com.idea2strategy.trading.application.port;

import com.idea2strategy.trading.application.stop.StopStepResult;
import com.idea2strategy.trading.domain.stop.BotStopSettlement;
import com.idea2strategy.trading.domain.stop.StopStep;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface BotStopSettlementStore {
    BotStopSettlement createOrLoad(BotStopSettlement desired);

    BotStopSettlement load(UUID settlementId);

    List<BotStopSettlement> loadRecoverable();

    BotStopSettlement recordStep(
            BotStopSettlement current, StopStep step, StopStepResult result, Instant occurredAt);
}
