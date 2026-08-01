package com.idea2strategy.trading.application.intent;

public final class OrderIntentBatchConflictException extends RuntimeException {
    public OrderIntentBatchConflictException(String message) {
        super(message);
    }

    public OrderIntentBatchConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
