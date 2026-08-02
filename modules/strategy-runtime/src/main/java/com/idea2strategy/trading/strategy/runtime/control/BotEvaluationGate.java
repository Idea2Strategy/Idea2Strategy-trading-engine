package com.idea2strategy.trading.strategy.runtime.control;

import java.util.UUID;

@FunctionalInterface
public interface BotEvaluationGate {
    void requireEvaluationAllowed(UUID botId);
}
