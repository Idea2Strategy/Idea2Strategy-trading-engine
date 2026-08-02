package com.idea2strategy.trading.application.port;

import com.idea2strategy.trading.application.stop.StopStepResult;
import java.util.UUID;

@FunctionalInterface
public interface OpenOrderCleanupPort {
    StopStepResult cancelOpenOrdersAndReleaseReservations(UUID botId, UUID operationId);
}
