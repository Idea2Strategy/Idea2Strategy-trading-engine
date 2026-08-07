package com.idea2strategy.trading.messaging.market;

import java.time.Duration;

/** Strategy candle periods supported by the live evaluation path. */
public enum MarketTimeframe {
    THIRTY_MINUTES("30m", Duration.ofMinutes(30), MarketEventType.BAR_30M),
    ONE_HOUR("1h", Duration.ofHours(1), MarketEventType.BAR_1H),
    FOUR_HOURS("4h", Duration.ofHours(4), MarketEventType.BAR_4H),
    ONE_DAY("1d", Duration.ofDays(1), MarketEventType.BAR_1D);

    private final String value;
    private final Duration duration;
    private final MarketEventType eventType;

    MarketTimeframe(String value, Duration duration, MarketEventType eventType) {
        this.value = value;
        this.duration = duration;
        this.eventType = eventType;
    }

    public String value() {
        return value;
    }

    public Duration duration() {
        return duration;
    }

    public MarketEventType eventType() {
        return eventType;
    }
}
