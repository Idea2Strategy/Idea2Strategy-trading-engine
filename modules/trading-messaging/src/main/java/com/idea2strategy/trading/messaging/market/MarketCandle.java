package com.idea2strategy.trading.messaging.market;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** One finalized strategy candle. A partial candle is only valid at an official session close. */
public record MarketCandle(
        UUID instrumentId,
        MarketTimeframe timeframe,
        Instant opensAt,
        Instant closesAt,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume,
        boolean partial) {

    public MarketCandle {
        instrumentId = Objects.requireNonNull(instrumentId, "instrumentId");
        timeframe = Objects.requireNonNull(timeframe, "timeframe");
        opensAt = Objects.requireNonNull(opensAt, "opensAt");
        closesAt = Objects.requireNonNull(closesAt, "closesAt");
        if (!opensAt.isBefore(closesAt)) {
            throw new IllegalArgumentException("opensAt must precede closesAt");
        }
        open = requireNonNegative(open, "open");
        high = requireNonNegative(high, "high");
        low = requireNonNegative(low, "low");
        close = requireNonNegative(close, "close");
        volume = requireNonNegative(volume, "volume");
        if (high.compareTo(open) < 0 || high.compareTo(close) < 0 || high.compareTo(low) < 0) {
            throw new IllegalArgumentException("high must be the greatest price");
        }
        if (low.compareTo(open) > 0 || low.compareTo(close) > 0) {
            throw new IllegalArgumentException("low must be the least price");
        }
    }

    public Map<String, BigDecimal> values() {
        return Map.of(
                "open", open,
                "high", high,
                "low", low,
                "close", close,
                "volume", volume,
                "partial", partial ? BigDecimal.ONE : BigDecimal.ZERO);
    }

    private static BigDecimal requireNonNegative(BigDecimal value, String field) {
        Objects.requireNonNull(value, field);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return value;
    }
}
