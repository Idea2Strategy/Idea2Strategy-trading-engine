package com.idea2strategy.trading.application.execution;

public final class FillDecisionConflictException extends RuntimeException {
    public FillDecisionConflictException() {
        super("fill decision identity was reused with different content");
    }
}
