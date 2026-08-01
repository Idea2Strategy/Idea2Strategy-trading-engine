package com.idea2strategy.trading.market.availability;

public enum MarketDataDegradationReason {
    PROVIDER_DISCONNECTED,
    STREAM_STALE,
    SEQUENCE_GAP,
    CONSUMER_LAG,
    CALENDAR_UNAVAILABLE
}
