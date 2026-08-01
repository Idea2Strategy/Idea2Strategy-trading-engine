package com.idea2strategy.trading.market.availability;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

public record MarketDataAvailabilityEvent(
        MarketDataAvailabilityEventType type,
        String symbol,
        Instant occurredAt,
        Set<MarketDataDegradationReason> reasons) {

    public MarketDataAvailabilityEvent {
        type = Objects.requireNonNull(type, "type");
        symbol = MarketDataAvailabilityInput.normalizeSymbol(symbol);
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        reasons = Set.copyOf(Objects.requireNonNull(reasons, "reasons"));
        if (reasons.isEmpty()) {
            throw new IllegalArgumentException("reasons must not be empty");
        }
    }
}
