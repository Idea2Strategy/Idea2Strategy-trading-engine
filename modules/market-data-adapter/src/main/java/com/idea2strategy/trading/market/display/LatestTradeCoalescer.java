package com.idea2strategy.trading.market.display;

import com.idea2strategy.trading.market.alpaca.AlpacaSipInboundMessage;
import com.idea2strategy.trading.messaging.market.DisplayPriceUpdate;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Retains interval OHLCV and the latest trade, bounding client fan-out to one update per flush. */
public final class LatestTradeCoalescer {
    private final Map<String, UUID> instruments;
    private final Clock clock;
    private final ConcurrentHashMap<String, Accumulator> pending = new ConcurrentHashMap<>();

    public LatestTradeCoalescer(Map<String, UUID> instruments, Clock clock) {
        Objects.requireNonNull(instruments, "instruments");
        Map<String, UUID> normalized = new java.util.HashMap<>();
        instruments.forEach((symbol, instrumentId) -> normalized.put(
                symbol.trim().toUpperCase(Locale.ROOT), Objects.requireNonNull(instrumentId, symbol)));
        this.instruments = Map.copyOf(normalized);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void accept(AlpacaSipInboundMessage.TradeTick tick) {
        Objects.requireNonNull(tick, "tick");
        String symbol = tick.symbol().trim().toUpperCase(Locale.ROOT);
        if (!instruments.containsKey(symbol)) {
            return;
        }
        pending.compute(symbol, (ignored, accumulator) -> accumulator == null
                ? new Accumulator(tick)
                : accumulator.add(tick));
    }

    public List<DisplayPriceUpdate> flush() {
        List<DisplayPriceUpdate> updates = new ArrayList<>();
        for (String symbol : List.copyOf(pending.keySet())) {
            Accumulator accumulator = pending.remove(symbol);
            if (accumulator != null) {
                AlpacaSipInboundMessage.TradeTick latest = accumulator.latest;
                updates.add(new DisplayPriceUpdate(
                        instruments.get(symbol),
                        symbol,
                        latest.price(),
                        latest.size(),
                        accumulator.earliest.price(),
                        accumulator.high,
                        accumulator.low,
                        latest.price(),
                        accumulator.volume,
                        accumulator.count,
                        latest.tradeId(),
                        latest.occurredAt(),
                        clock.instant()));
            }
        }
        updates.sort(java.util.Comparator.comparing(DisplayPriceUpdate::symbol));
        return List.copyOf(updates);
    }

    private static final class Accumulator {
        private final AlpacaSipInboundMessage.TradeTick earliest;
        private final AlpacaSipInboundMessage.TradeTick latest;
        private final BigDecimal high;
        private final BigDecimal low;
        private final BigDecimal volume;
        private final long count;

        private Accumulator(AlpacaSipInboundMessage.TradeTick tick) {
            this(tick, tick, tick.price(), tick.price(), tick.size(), 1);
        }

        private Accumulator(
                AlpacaSipInboundMessage.TradeTick earliest,
                AlpacaSipInboundMessage.TradeTick latest,
                BigDecimal high,
                BigDecimal low,
                BigDecimal volume,
                long count) {
            this.earliest = earliest;
            this.latest = latest;
            this.high = high;
            this.low = low;
            this.volume = volume;
            this.count = count;
        }

        private Accumulator add(AlpacaSipInboundMessage.TradeTick tick) {
            AlpacaSipInboundMessage.TradeTick first = compare(tick, earliest) < 0 ? tick : earliest;
            AlpacaSipInboundMessage.TradeTick newest = compare(tick, latest) >= 0 ? tick : latest;
            return new Accumulator(
                    first,
                    newest,
                    high.max(tick.price()),
                    low.min(tick.price()),
                    volume.add(tick.size()),
                    count + 1);
        }

        private static int compare(
                AlpacaSipInboundMessage.TradeTick left,
                AlpacaSipInboundMessage.TradeTick right) {
            int occurredAt = left.occurredAt().compareTo(right.occurredAt());
            return occurredAt != 0 ? occurredAt : Long.compare(left.tradeId(), right.tradeId());
        }
    }
}
