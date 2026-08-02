package com.idea2strategy.trading.application.stop;

public record StopStepResult(StopStepResultStatus status, String detail) {
    public StopStepResult {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (detail == null || detail.isBlank()) {
            throw new IllegalArgumentException("detail must not be blank");
        }
    }

    public static StopStepResult completed(String detail) {
        return new StopStepResult(StopStepResultStatus.COMPLETED, detail);
    }

    public static StopStepResult partial(String detail) {
        return new StopStepResult(StopStepResultStatus.PARTIAL, detail);
    }

    public static StopStepResult retryable(String detail) {
        return new StopStepResult(StopStepResultStatus.RETRYABLE, detail);
    }

    public static StopStepResult terminalFailure(String detail) {
        return new StopStepResult(StopStepResultStatus.TERMINAL_FAILURE, detail);
    }
}
