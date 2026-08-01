package com.idea2strategy.trading.strategy.runtime.judgment;

import java.util.Objects;

public final class JudgmentAppendException extends RuntimeException {
    private final JudgmentAppendFailure failure;

    JudgmentAppendException(JudgmentAppendFailure failure, String message) {
        super(message);
        this.failure = Objects.requireNonNull(failure, "failure must not be null");
    }

    public JudgmentAppendFailure failure() {
        return failure;
    }
}
