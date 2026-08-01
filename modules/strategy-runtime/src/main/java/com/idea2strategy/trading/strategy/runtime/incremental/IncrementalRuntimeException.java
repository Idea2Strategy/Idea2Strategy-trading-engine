package com.idea2strategy.trading.strategy.runtime.incremental;

import java.util.Objects;

public final class IncrementalRuntimeException extends RuntimeException {
    private final IncrementalRuntimeFailure failure;

    public IncrementalRuntimeException(IncrementalRuntimeFailure failure, String detail) {
        super(Objects.requireNonNull(failure, "failure").name() + ": " + detail);
        this.failure = failure;
    }

    public IncrementalRuntimeException(IncrementalRuntimeFailure failure, String detail, Throwable cause) {
        super(Objects.requireNonNull(failure, "failure").name() + ": " + detail, cause);
        this.failure = failure;
    }

    public IncrementalRuntimeFailure failure() {
        return failure;
    }
}
