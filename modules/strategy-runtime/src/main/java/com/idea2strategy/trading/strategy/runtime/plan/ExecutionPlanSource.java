package com.idea2strategy.trading.strategy.runtime.plan;

import java.util.Optional;
import java.util.UUID;

@FunctionalInterface
public interface ExecutionPlanSource {
    Optional<ExecutionPlanSourceSnapshot> findByBotId(UUID botId);
}
