package com.idea2strategy.trading.worker.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.idea2strategy.trading.messaging.market.MarketEventEnvelope;
import com.idea2strategy.trading.messaging.market.MarketEventType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BasicMarketSignalStateTest {

    private static final UUID INSTRUMENT = UUID.fromString("00000000-0000-4000-8000-000000000301");

    @Test
    void publishesRollingOneMinuteBarsAndOnlyClosesAnAggregateOnRollover() {
        BasicMarketSignalState state = new BasicMarketSignalState();

        Map<String, String> first = state.accept(bar(1, "2026-08-07T13:31:00Z", "100", "10"));
        Map<String, String> second = state.accept(bar(2, "2026-08-07T13:32:00Z", "101", "20"));
        Map<String, String> rollover = state.accept(bar(3, "2026-08-07T13:35:00Z", "103", "30"));

        assertEquals("100,101,103", rollover.get("closes.1m"));
        assertEquals("true", first.get("bar.closed.1m"));
        assertEquals("false", second.get("bar.closed.5m"));
        assertEquals("true", rollover.get("bar.closed.5m"));
        assertEquals("101", rollover.get("price.5m"));
        assertEquals("30", rollover.get("volumes.5m"));
    }

    @Test
    void emitsOneScheduleTriggerPerObservedTradingDay() {
        BasicMarketSignalState state = new BasicMarketSignalState();

        Map<String, String> first = state.accept(bar(1, "2026-08-03T13:31:00Z", "100", "10"));
        Map<String, String> sameDay = state.accept(bar(2, "2026-08-03T13:32:00Z", "101", "10"));
        Map<String, String> nextDay = state.accept(bar(3, "2026-08-04T13:31:00Z", "102", "10"));

        assertEquals("true", first.get("schedule.newTradingDay"));
        assertEquals("true", first.get("schedule.weekFirstTradingDay"));
        assertEquals("false", sameDay.get("schedule.newTradingDay"));
        assertEquals("true", nextDay.get("schedule.newTradingDay"));
        assertEquals("2", nextDay.get("schedule.tradingDayIndex"));
    }

    private static MarketEventEnvelope bar(long sequence, String at, String close, String volume) {
        Instant instant = Instant.parse(at);
        BigDecimal price = new BigDecimal(close);
        return new MarketEventEnvelope(
                "event-" + sequence, 1, INSTRUMENT, "alpaca", "sip",
                MarketEventType.BAR_1M, "provider-" + sequence, instant, instant,
                sequence, 0, null, Map.of(
                        "open", price.subtract(BigDecimal.ONE), "high", price,
                        "low", price.subtract(BigDecimal.TEN), "close", price,
                        "volume", new BigDecimal(volume)));
    }
}
