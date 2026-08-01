package com.idea2strategy.trading.market.alpaca;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class MarketStreamHealthTracker {
    private final Clock clock;
    private final Duration staleAfter;
    private final MarketDataRecoveryBoundary recoveryBoundary;
    private final Map<String, StreamState> streams = new HashMap<>();

    public MarketStreamHealthTracker(
            Clock clock,
            Duration staleAfter,
            MarketDataRecoveryBoundary recoveryBoundary) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.staleAfter = Objects.requireNonNull(staleAfter, "staleAfter");
        if (staleAfter.isZero() || staleAfter.isNegative()) {
            throw new IllegalArgumentException("staleAfter must be positive");
        }
        this.recoveryBoundary = Objects.requireNonNull(recoveryBoundary, "recoveryBoundary");
    }

    public synchronized MarketStreamStatus record(String symbol, long sequence, Instant receivedAt) {
        String normalizedSymbol = normalizeSymbol(symbol);
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
        Objects.requireNonNull(receivedAt, "receivedAt");
        StreamState previous = streams.get(normalizedSymbol);
        if (previous != null && sequence > previous.sequence() + 1) {
            recoveryBoundary.recover(
                    normalizedSymbol,
                    new MissingSequenceRange(previous.sequence() + 1, sequence - 1));
            streams.put(normalizedSymbol, new StreamState(sequence, receivedAt, MarketStreamStatus.GAP));
            return MarketStreamStatus.GAP;
        }
        if (previous == null || sequence > previous.sequence()) {
            streams.put(normalizedSymbol, new StreamState(sequence, receivedAt, MarketStreamStatus.FRESH));
        }
        return status(normalizedSymbol);
    }

    public synchronized MarketStreamStatus status(String symbol) {
        StreamState state = streams.get(normalizeSymbol(symbol));
        if (state == null) {
            return MarketStreamStatus.STALE;
        }
        if (state.status() == MarketStreamStatus.GAP) {
            return MarketStreamStatus.GAP;
        }
        return clock.instant().isAfter(state.receivedAt().plus(staleAfter))
                ? MarketStreamStatus.STALE
                : MarketStreamStatus.FRESH;
    }

    public synchronized void markRecovered(String symbol, long throughSequence, Instant recoveredAt) {
        String normalizedSymbol = normalizeSymbol(symbol);
        StreamState state = streams.get(normalizedSymbol);
        if (state == null || throughSequence < state.sequence()) {
            throw new IllegalStateException("recovery must cover the current sequence");
        }
        streams.put(normalizedSymbol, new StreamState(throughSequence, Objects.requireNonNull(recoveredAt, "recoveredAt"), MarketStreamStatus.FRESH));
    }

    private static String normalizeSymbol(String symbol) {
        String normalized = Objects.requireNonNull(symbol, "symbol").trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        return normalized;
    }

    private record StreamState(long sequence, Instant receivedAt, MarketStreamStatus status) {}
}
