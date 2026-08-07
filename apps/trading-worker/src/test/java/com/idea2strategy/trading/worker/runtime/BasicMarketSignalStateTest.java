package com.idea2strategy.trading.worker.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
    void publishesOnlyFinalizedStrategyCandles() {
        BasicMarketSignalState state = new BasicMarketSignalState();

        Map<String, String> first = state.accept(ready(
                1, "2026-08-07T14:00:00Z", "100", "10", false));
        Map<String, String> second = state.accept(ready(
                2, "2026-08-07T14:30:00Z", "101", "20", true));

        assertEquals("100,101", second.get("closes.30m"));
        assertEquals("101", second.get("closes.1h"));
        assertEquals("true", first.get("bar.closed.30m"));
        assertEquals("false", first.get("bar.closed.1h"));
        assertEquals("true", second.get("bar.closed.1h"));
        assertNull(second.get("closes.1m"));
        assertNull(first.get("bar.closed.1m"));
        assertNull(second.get("bar.closed.5m"));
    }

    @Test
    void emitsOneScheduleTriggerPerObservedTradingDay() {
        BasicMarketSignalState state = new BasicMarketSignalState();

        Map<String, String> first = state.accept(ready(
                1, "2026-08-03T14:00:00Z", "100", "10", false));
        Map<String, String> sameDay = state.accept(ready(
                2, "2026-08-03T14:30:00Z", "101", "10", true));
        Map<String, String> nextDay = state.accept(ready(
                3, "2026-08-04T14:00:00Z", "102", "10", false));

        assertEquals("true", first.get("schedule.newTradingDay"));
        assertEquals("true", first.get("schedule.weekFirstTradingDay"));
        assertEquals("false", sameDay.get("schedule.newTradingDay"));
        assertEquals("true", nextDay.get("schedule.newTradingDay"));
        assertEquals("2", nextDay.get("schedule.tradingDayIndex"));
    }

    private static MarketEventEnvelope ready(
            long sequence, String at, String close, String volume, boolean closesHour) {
        Instant instant = Instant.parse(at);
        BigDecimal price = new BigDecimal(close);
        Map<String, BigDecimal> values = new java.util.LinkedHashMap<>();
        values.put("close", price);
        values.put("closed30m", BigDecimal.ONE);
        values.put("closed1h", closesHour ? BigDecimal.ONE : BigDecimal.ZERO);
        values.put("closed4h", BigDecimal.ZERO);
        values.put("closed1d", BigDecimal.ZERO);
        putCandle(values, "30m", price, volume);
        if (closesHour) {
            putCandle(values, "1h", price, volume);
        }
        return new MarketEventEnvelope(
                "event-" + sequence, 2, INSTRUMENT, "alpaca", "sip",
                MarketEventType.MARKET_EVALUATION_READY, "provider-" + sequence,
                instant, instant, sequence, 0, null, values);
    }

    private static void putCandle(
            Map<String, BigDecimal> values, String suffix, BigDecimal close, String volume) {
        values.put("open" + suffix, close.subtract(BigDecimal.ONE));
        values.put("high" + suffix, close);
        values.put("low" + suffix, close.subtract(BigDecimal.TEN));
        values.put("close" + suffix, close);
        values.put("volume" + suffix, new BigDecimal(volume));
    }
}
