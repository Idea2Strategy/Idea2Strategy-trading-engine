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
        BasicMarketSignalState state = stateEligibleFrom("2026-08-07T13:30:00Z");

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
        BasicMarketSignalState state = stateEligibleFrom("2026-08-03T13:30:00Z");

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

    @Test
    void tradingDayIndexSurvivesARestart() {
        BasicMarketSignalState beforeRestart = stateEligibleFrom("2025-11-24T14:30:00Z");
        beforeRestart.accept(ready(
                1, "2025-11-24T14:30:00Z", "100", "10", false));
        beforeRestart.accept(ready(
                2, "2025-11-26T14:30:00Z", "101", "10", false));

        BasicMarketSignalState afterRestart = stateEligibleFrom("2025-11-24T14:30:00Z");
        Map<String, String> resumed = afterRestart.accept(ready(
                3, "2025-12-01T14:30:00Z", "102", "10", false));

        // Nov 24, 25, 26, 28, and Dec 1 are the five NYSE sessions in this period.
        assertEquals("5", resumed.get("schedule.tradingDayIndex"));
    }

    /**
     * The session closes when its daily candle does, whatever the clock says.
     *
     * <p>These four cases are the live half of the backtest's {@code session.close} rule. The
     * market closes at 13:00 ET the day after Thanksgiving, at Christmas Eve, and on July 3, so a
     * fixed 16:00 test never fired on those days and a {@code SESSION_CLOSE} exit silently did not
     * run, while the backtest read the session's real close and exited as written.
     */
    @Test
    void closesTheSessionWhenTheDailyCandleCloses() {
        BasicMarketSignalState state = stateEligibleFrom("2025-11-28T14:30:00Z");

        // 2025-11-28, the day after Thanksgiving: the session ends 18:00Z, which is 13:00 ET.
        Map<String, String> beforeEarlyClose = state.accept(ready(
                1, "2025-11-28T17:30:00Z", "100", "10", false, false));
        Map<String, String> atEarlyClose = state.accept(ready(
                2, "2025-11-28T18:00:00Z", "101", "10", true, true));

        assertEquals("false", beforeEarlyClose.get("session.close"));
        assertEquals("true", atEarlyClose.get("session.close"));
    }

    @Test
    void doesNotCloseTheSessionAtAnHourThatOnlyUsuallyEndsIt() {
        BasicMarketSignalState state = stateEligibleFrom("2025-12-01T14:30:00Z");

        // 21:00Z is 16:00 ET, the usual close -- but this event's daily candle did not finalize,
        // so the session has not ended and the hour alone must not say that it has.
        Map<String, String> values = state.accept(ready(
                1, "2025-12-01T21:00:00Z", "100", "10", true, false));

        assertEquals("false", values.get("session.close"));
    }

    @Test
    void closesTheSessionOnARegularDayToo() {
        BasicMarketSignalState state = stateEligibleFrom("2025-12-01T14:30:00Z");

        Map<String, String> values = state.accept(ready(
                1, "2025-12-01T21:00:00Z", "100", "10", true, true));

        assertEquals("true", values.get("session.close"));
    }

    private static BasicMarketSignalState stateEligibleFrom(String at) {
        return new BasicMarketSignalState(Instant.parse(at));
    }

    private static MarketEventEnvelope ready(
            long sequence, String at, String close, String volume, boolean closesHour) {
        return ready(sequence, at, close, volume, closesHour, false);
    }

    private static MarketEventEnvelope ready(
            long sequence,
            String at,
            String close,
            String volume,
            boolean closesHour,
            boolean closesDay) {
        Instant instant = Instant.parse(at);
        BigDecimal price = new BigDecimal(close);
        Map<String, BigDecimal> values = new java.util.LinkedHashMap<>();
        values.put("close", price);
        values.put("closed30m", BigDecimal.ONE);
        values.put("closed1h", closesHour ? BigDecimal.ONE : BigDecimal.ZERO);
        values.put("closed4h", BigDecimal.ZERO);
        values.put("closed1d", closesDay ? BigDecimal.ONE : BigDecimal.ZERO);
        putCandle(values, "30m", price, volume);
        if (closesHour) {
            putCandle(values, "1h", price, volume);
        }
        if (closesDay) {
            putCandle(values, "1d", price, volume);
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
