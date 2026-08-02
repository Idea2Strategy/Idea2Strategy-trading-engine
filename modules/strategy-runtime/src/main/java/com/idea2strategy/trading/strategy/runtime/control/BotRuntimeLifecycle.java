package com.idea2strategy.trading.strategy.runtime.control;

import com.idea2strategy.trading.strategy.runtime.plan.LoadedExecutionPlan;
import java.time.Instant;
import java.util.UUID;

public interface BotRuntimeLifecycle {
    void start(LoadedExecutionPlan plan, Instant executionEligibleFrom);

    void stop(UUID botId, String reasonCode);
}
