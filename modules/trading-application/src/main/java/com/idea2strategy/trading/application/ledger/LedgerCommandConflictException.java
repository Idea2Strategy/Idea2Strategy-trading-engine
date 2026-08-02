package com.idea2strategy.trading.application.ledger;

public final class LedgerCommandConflictException extends RuntimeException {
    public LedgerCommandConflictException(String message) {
        super(message);
    }
}
