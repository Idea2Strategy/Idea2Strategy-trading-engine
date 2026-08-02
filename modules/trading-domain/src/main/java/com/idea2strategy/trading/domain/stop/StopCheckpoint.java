package com.idea2strategy.trading.domain.stop;

public enum StopCheckpoint {
    REQUESTED,
    WORK_BLOCKED,
    ORDERS_CLEANED,
    LIQUIDATING,
    STOPPED,
    SETTLEMENT_FAILED
}
