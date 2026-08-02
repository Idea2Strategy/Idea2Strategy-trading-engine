package com.idea2strategy.trading.strategy.runtime.control;

import java.util.Optional;
import java.util.UUID;

@FunctionalInterface
public interface StrategyBotSnapshotSource {
    Optional<String> findCompiledPlan(UUID botId);
}
