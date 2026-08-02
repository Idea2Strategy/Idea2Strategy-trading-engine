package com.idea2strategy.trading.domain.execution;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RecordedMarketSnapshot(
        UUID snapshotId,
        UUID instrumentId,
        Instant observedAt,
        BigDecimal bidPrice,
        BigDecimal bidSize,
        BigDecimal askPrice,
        BigDecimal askSize,
        BigDecimal lastTradePrice,
        BigDecimal lastTradeSize,
        BigDecimal trailingReferencePrice) {

    public RecordedMarketSnapshot {
        snapshotId = required(snapshotId, "snapshotId");
        instrumentId = required(instrumentId, "instrumentId");
        observedAt = required(observedAt, "observedAt");
        bidPrice = positive(bidPrice, "bidPrice");
        askPrice = positive(askPrice, "askPrice");
        bidSize = nonNegative(bidSize, "bidSize");
        askSize = nonNegative(askSize, "askSize");
        lastTradePrice = positive(lastTradePrice, "lastTradePrice");
        lastTradeSize = nonNegative(lastTradeSize, "lastTradeSize");
        trailingReferencePrice = optionalPositive(trailingReferencePrice, "trailingReferencePrice");
        if (bidPrice.compareTo(askPrice) > 0) {
            throw new IllegalArgumentException("bidPrice must not exceed askPrice");
        }
    }

    private static BigDecimal positive(BigDecimal value, String name) {
        BigDecimal normalized = required(value, name).stripTrailingZeros();
        if (normalized.signum() <= 0) throw new IllegalArgumentException(name + " must be positive");
        return normalized;
    }

    private static BigDecimal nonNegative(BigDecimal value, String name) {
        BigDecimal normalized = required(value, name).stripTrailingZeros();
        if (normalized.signum() < 0) throw new IllegalArgumentException(name + " must not be negative");
        return normalized;
    }

    private static BigDecimal optionalPositive(BigDecimal value, String name) {
        return value == null ? null : positive(value, name);
    }

    private static <T> T required(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " must not be null");
        return value;
    }
}
