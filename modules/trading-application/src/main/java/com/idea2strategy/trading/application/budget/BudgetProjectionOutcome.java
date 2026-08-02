package com.idea2strategy.trading.application.budget;

/** What a projection write did to the canonical row. */
public enum BudgetProjectionOutcome {

    /** The row did not exist and now holds this projection. */
    CREATED,

    /** The row held an older event sequence and now holds this projection. */
    ADVANCED,

    /** The row already held exactly this projection, so nothing was written. */
    UNCHANGED
}
