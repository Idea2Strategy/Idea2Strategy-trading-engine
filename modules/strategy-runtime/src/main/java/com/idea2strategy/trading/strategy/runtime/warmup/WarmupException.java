package com.idea2strategy.trading.strategy.runtime.warmup;

import java.util.Objects;

public final class WarmupException extends RuntimeException {
    private final WarmupFailure failure;

    public WarmupException(WarmupFailure failure, String detail) {
        super(Objects.requireNonNull(failure, "failure").name() + ": " + detail);
        this.failure = failure;
    }

    public WarmupFailure failure() {
        return failure;
    }
}
