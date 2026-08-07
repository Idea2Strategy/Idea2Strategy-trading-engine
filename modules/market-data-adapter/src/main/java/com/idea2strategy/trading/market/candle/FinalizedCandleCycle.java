package com.idea2strategy.trading.market.candle;

import com.idea2strategy.trading.market.session.OfficialMarketSession;
import com.idea2strategy.trading.messaging.market.MarketCandle;
import com.idea2strategy.trading.messaging.market.MarketEventEnvelope;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** Executes one finalized 30m boundary across the approved instrument catalog. */
public final class FinalizedCandleCycle {
    private final AlpacaThirtyMinuteBarsClient client;
    private final SessionAlignedCandleAggregator aggregator;
    private final MarketCandleEventFactory candleEvents;
    private final MarketEvaluationCoordinator evaluations;
    private final Consumer<MarketEventEnvelope> sink;
    private final Clock clock;
    private final int batchSize;

    public FinalizedCandleCycle(
            AlpacaThirtyMinuteBarsClient client,
            Consumer<MarketEventEnvelope> sink,
            Clock clock,
            int batchSize) {
        this.client = Objects.requireNonNull(client, "client");
        this.aggregator = new SessionAlignedCandleAggregator();
        this.candleEvents = new MarketCandleEventFactory();
        this.evaluations = new MarketEvaluationCoordinator();
        this.sink = Objects.requireNonNull(sink, "sink");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("batchSize must be between 1 and 500");
        }
        this.batchSize = batchSize;
    }

    public FinalizedCandleCycleResult run(
            Map<String, UUID> instruments,
            OfficialMarketSession session,
            Instant boundary) {
        validateBoundary(session, boundary);
        List<Map.Entry<String, UUID>> entries = new ArrayList<>(instruments.entrySet());
        int evaluated = 0;
        int missing = 0;
        for (int offset = 0; offset < entries.size(); offset += batchSize) {
            Map<String, UUID> batch = new LinkedHashMap<>();
            entries.subList(offset, Math.min(entries.size(), offset + batchSize))
                    .forEach(entry -> batch.put(entry.getKey(), entry.getValue()));
            Map<String, List<MarketCandle>> fetched = client.fetch(batch, session, boundary);
            for (Map.Entry<String, UUID> instrument : batch.entrySet()) {
                List<MarketCandle> source = fetched.getOrDefault(instrument.getKey(), List.of());
                if (source.isEmpty() || !source.getLast().closesAt().equals(boundary)) {
                    missing++;
                    continue;
                }
                List<MarketCandle> closed;
                try {
                    closed = aggregator.closedAt(source, session, boundary);
                } catch (IllegalArgumentException missingSource) {
                    missing++;
                    continue;
                }
                Instant receivedAt = clock.instant();
                closed.forEach(candle -> sink.accept(candleEvents.event(candle, receivedAt)));
                sink.accept(evaluations.ready(instrument.getValue(), boundary, closed, receivedAt));
                evaluated++;
            }
        }
        return new FinalizedCandleCycleResult(evaluated, missing);
    }

    private static void validateBoundary(OfficialMarketSession session, Instant boundary) {
        long elapsed = Duration.between(session.opensAt(), boundary).toSeconds();
        if (elapsed <= 0 || elapsed % Duration.ofMinutes(30).toSeconds() != 0
                || boundary.isAfter(session.closesAt())) {
            throw new IllegalArgumentException("boundary must be a finalized 30m session boundary");
        }
    }

    public record FinalizedCandleCycleResult(int evaluatedInstrumentCount, int missingInstrumentCount) {}
}
