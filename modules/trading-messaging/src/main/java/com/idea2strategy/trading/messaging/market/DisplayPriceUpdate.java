package com.idea2strategy.trading.messaging.market;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A coalesced display-only price update. It is never a strategy evaluation trigger. */
public record DisplayPriceUpdate(
        UUID instrumentId,
        String symbol,
        BigDecimal price,
        BigDecimal lastTradeSize,
        BigDecimal intervalOpen,
        BigDecimal intervalHigh,
        BigDecimal intervalLow,
        BigDecimal intervalClose,
        BigDecimal intervalVolume,
        long intervalTradeCount,
        long providerTradeId,
        Instant occurredAt,
        Instant publishedAt) {
    public DisplayPriceUpdate {
        instrumentId = Objects.requireNonNull(instrumentId, "instrumentId");
        symbol = requireText(symbol, "symbol");
        price = requireNonNegative(price, "price");
        lastTradeSize = requireNonNegative(lastTradeSize, "lastTradeSize");
        intervalOpen = requireNonNegative(intervalOpen, "intervalOpen");
        intervalHigh = requireNonNegative(intervalHigh, "intervalHigh");
        intervalLow = requireNonNegative(intervalLow, "intervalLow");
        intervalClose = requireNonNegative(intervalClose, "intervalClose");
        intervalVolume = requireNonNegative(intervalVolume, "intervalVolume");
        if (intervalHigh.compareTo(intervalOpen.max(intervalClose)) < 0) {
            throw new IllegalArgumentException("intervalHigh must cover interval open and close");
        }
        if (intervalLow.compareTo(intervalOpen.min(intervalClose)) > 0) {
            throw new IllegalArgumentException("intervalLow must cover interval open and close");
        }
        if (price.compareTo(intervalClose) != 0) {
            throw new IllegalArgumentException("price must equal intervalClose");
        }
        if (intervalTradeCount < 1) {
            throw new IllegalArgumentException("intervalTradeCount must be positive");
        }
        if (providerTradeId < 0) {
            throw new IllegalArgumentException("providerTradeId must not be negative");
        }
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        publishedAt = Objects.requireNonNull(publishedAt, "publishedAt");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static BigDecimal requireNonNegative(BigDecimal value, String field) {
        Objects.requireNonNull(value, field);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return value;
    }
}
