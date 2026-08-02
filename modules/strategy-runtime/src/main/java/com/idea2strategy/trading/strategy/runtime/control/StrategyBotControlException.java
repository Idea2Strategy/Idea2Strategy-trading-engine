package com.idea2strategy.trading.strategy.runtime.control;

import java.util.Objects;

public class StrategyBotControlException extends RuntimeException {
    private final BotControlFailure failure;

    public StrategyBotControlException(BotControlFailure failure, String detail) {
        super(Objects.requireNonNull(detail, "detail"));
        this.failure = Objects.requireNonNull(failure, "failure");
    }

    public StrategyBotControlException(BotControlFailure failure, String detail, Throwable cause) {
        super(Objects.requireNonNull(detail, "detail"), cause);
        this.failure = Objects.requireNonNull(failure, "failure");
    }

    public BotControlFailure failure() {
        return failure;
    }
}
