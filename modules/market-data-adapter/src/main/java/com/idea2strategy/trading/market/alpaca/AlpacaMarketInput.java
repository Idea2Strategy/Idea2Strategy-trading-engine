package com.idea2strategy.trading.market.alpaca;

import com.idea2strategy.trading.messaging.market.MarketEventType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record AlpacaMarketInput(
        MarketEventType eventType,
        String providerEventId,
        String symbol,
        String feed,
        Instant occurredAt,
        Instant receivedAt,
        long sequence,
        int revision,
        Map<String, BigDecimal> values) {

    public AlpacaMarketInput {
        eventType = Objects.requireNonNull(eventType, "eventType");
        providerEventId = requireText(providerEventId, "providerEventId");
        symbol = requireText(symbol, "symbol");
        feed = requireText(feed, "feed");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        values = Map.copyOf(Objects.requireNonNull(values, "values"));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
