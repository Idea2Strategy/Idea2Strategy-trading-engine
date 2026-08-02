package com.idea2strategy.trading.application.event;

/** Raised when an idempotency key is already recorded against different work. */
public final class BotEventConflictException extends RuntimeException {

    public BotEventConflictException(String message) {
        super(message);
    }

    public BotEventConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
