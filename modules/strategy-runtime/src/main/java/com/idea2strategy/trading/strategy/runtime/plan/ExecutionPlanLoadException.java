package com.idea2strategy.trading.strategy.runtime.plan;

import java.util.Objects;

public final class ExecutionPlanLoadException extends IllegalStateException {
    private final ExecutionPlanLoadFailure failure;

    ExecutionPlanLoadException(ExecutionPlanLoadFailure failure, String detail) {
        super(Objects.requireNonNull(failure, "failure").name() + ": " + detail);
        this.failure = failure;
    }

    public ExecutionPlanLoadFailure failure() {
        return failure;
    }
}
