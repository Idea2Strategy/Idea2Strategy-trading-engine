package com.idea2strategy.trading.application.order;

public final class OrderLifecycleVersionConflictException extends OrderLifecycleConflictException {
    public OrderLifecycleVersionConflictException(String message) {
        super(message);
    }

    public OrderLifecycleVersionConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
