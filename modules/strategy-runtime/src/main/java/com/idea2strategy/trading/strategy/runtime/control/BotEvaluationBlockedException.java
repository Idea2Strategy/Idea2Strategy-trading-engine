package com.idea2strategy.trading.strategy.runtime.control;

import java.util.UUID;

public final class BotEvaluationBlockedException extends StrategyBotControlException {
    public BotEvaluationBlockedException(UUID botId) {
        super(BotControlFailure.EVALUATION_BLOCKED, "Bot is not running: " + botId);
    }
}
