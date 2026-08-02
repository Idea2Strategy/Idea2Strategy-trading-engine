package com.idea2strategy.trading.domain.stop;

public enum StopStep {
    BLOCK_NEW_WORK,
    CANCEL_ORDERS_AND_RELEASE,
    LIQUIDATE_POSITIONS
}
