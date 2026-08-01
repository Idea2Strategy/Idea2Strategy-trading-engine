package com.idea2strategy.trading.application.order;

public class OrderLifecycleConflictException extends RuntimeException {
    public OrderLifecycleConflictException(String message) {
        super(message);
    }

    public OrderLifecycleConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
