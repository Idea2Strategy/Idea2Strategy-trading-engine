package com.idea2strategy.trading.domain.order;

public enum OrderStatus {
    ACCEPTED,
    PARTIALLY_FILLED,
    FILLED,
    CANCELLED,
    EXPIRED,
    REJECTED;

    /** True when no further transition is possible and nothing is left outstanding. */
    public boolean isTerminal() {
        return this == FILLED || this == CANCELLED || this == EXPIRED || this == REJECTED;
    }
}
