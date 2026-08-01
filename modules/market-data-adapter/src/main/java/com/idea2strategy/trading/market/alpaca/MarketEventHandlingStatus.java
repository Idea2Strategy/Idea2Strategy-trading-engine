package com.idea2strategy.trading.market.alpaca;

public enum MarketEventHandlingStatus {
    APPLIED,
    DUPLICATE,
    OUT_OF_ORDER,
    CORRECTION_APPLIED,
    STALE_CORRECTION,
    ORPHAN_CORRECTION,
    SEQUENCE_CONFLICT
}
