package com.idea2strategy.trading.persistence.projection;

public final class ProjectionAttributionConflictException extends RuntimeException {
    public ProjectionAttributionConflictException(String message) {
        super(message);
    }

    public ProjectionAttributionConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
