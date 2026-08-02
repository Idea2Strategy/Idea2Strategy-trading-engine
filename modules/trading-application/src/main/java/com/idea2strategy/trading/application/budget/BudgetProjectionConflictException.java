package com.idea2strategy.trading.application.budget;

/** A budget projection could not be stored against what canonical already holds. */
public final class BudgetProjectionConflictException extends RuntimeException {

    public BudgetProjectionConflictException(String message) {
        super(message);
    }
}
