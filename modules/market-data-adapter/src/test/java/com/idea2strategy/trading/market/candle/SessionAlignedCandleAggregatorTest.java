package com.idea2strategy.trading.market.candle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idea2strategy.trading.market.session.OfficialMarketSession;
import com.idea2strategy.trading.messaging.market.MarketCandle;
import com.idea2strategy.trading.messaging.market.MarketTimeframe;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SessionAlignedCandleAggregatorTest {
    private static final UUID INSTRUMENT = UUID.fromString("68ed5d6c-7472-44d7-a606-bb1d32726d80");

    @Test
    void closesThirtyMinuteAndHourlyCandlesAtTheSameBoundary() {
        OfficialMarketSession session = session("2026-08-03T13:30:00Z", "2026-08-03T20:00:00Z");
        List<MarketCandle> source = List.of(
                candle("2026-08-03T13:30:00Z", "2026-08-03T14:00:00Z", "100", "102", "99", "101", "10"),
                candle("2026-08-03T14:00:00Z", "2026-08-03T14:30:00Z", "101", "104", "100", "103", "20"));

        List<MarketCandle> closed = new SessionAlignedCandleAggregator()
                .closedAt(source, session, Instant.parse("2026-08-03T14:30:00Z"));

        assertEquals(List.of(MarketTimeframe.THIRTY_MINUTES, MarketTimeframe.ONE_HOUR),
                closed.stream().map(MarketCandle::timeframe).toList());
        MarketCandle hourly = closed.get(1);
        assertEquals(new BigDecimal("100"), hourly.open());
        assertEquals(new BigDecimal("104"), hourly.high());
        assertEquals(new BigDecimal("99"), hourly.low());
        assertEquals(new BigDecimal("103"), hourly.close());
        assertEquals(new BigDecimal("30"), hourly.volume());
        assertTrue(!hourly.partial());
    }

    @Test
    void preservesFinalPartialHourAndFourHourCandleAtAnEarlyClose() {
        OfficialMarketSession session = session("2026-11-27T14:30:00Z", "2026-11-27T18:00:00Z");
        List<MarketCandle> source = candles(
                Instant.parse("2026-11-27T14:30:00Z"), 7);

        List<MarketCandle> closed = new SessionAlignedCandleAggregator()
                .closedAt(source, session, session.closesAt());

        assertEquals(
                List.of(
                        MarketTimeframe.THIRTY_MINUTES,
                        MarketTimeframe.ONE_HOUR,
                        MarketTimeframe.FOUR_HOURS,
                        MarketTimeframe.ONE_DAY),
                closed.stream().map(MarketCandle::timeframe).toList());
        assertTrue(closed.get(1).partial());
        assertTrue(closed.get(2).partial());
        assertTrue(!closed.get(3).partial());
        assertEquals(session.opensAt(), closed.get(2).opensAt());
        assertEquals(session.closesAt(), closed.get(2).closesAt());
    }

    @Test
    void aMissingEarlierBarDoesNotSuppressALaterValidThirtyMinuteEvaluation() {
        OfficialMarketSession session = session("2026-08-03T13:30:00Z", "2026-08-03T20:00:00Z");
        List<MarketCandle> source = List.of(
                candle("2026-08-03T13:30:00Z", "2026-08-03T14:00:00Z", "100", "101", "99", "100", "10"),
                candle("2026-08-03T14:30:00Z", "2026-08-03T15:00:00Z", "101", "102", "100", "101", "10"));

        List<MarketCandle> closed = new SessionAlignedCandleAggregator()
                .closedAt(source, session, Instant.parse("2026-08-03T15:00:00Z"));

        assertEquals(List.of(MarketTimeframe.THIRTY_MINUTES),
                closed.stream().map(MarketCandle::timeframe).toList());
    }

    private static List<MarketCandle> candles(Instant firstOpen, int count) {
        List<MarketCandle> candles = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            Instant open = firstOpen.plusSeconds(index * 1800L);
            candles.add(candle(
                    open.toString(), open.plusSeconds(1800).toString(),
                    Integer.toString(100 + index), Integer.toString(102 + index),
                    Integer.toString(99 + index), Integer.toString(101 + index), "10"));
        }
        return candles;
    }

    private static MarketCandle candle(
            String opensAt, String closesAt, String open, String high, String low, String close, String volume) {
        return new MarketCandle(
                INSTRUMENT,
                MarketTimeframe.THIRTY_MINUTES,
                Instant.parse(opensAt),
                Instant.parse(closesAt),
                new BigDecimal(open),
                new BigDecimal(high),
                new BigDecimal(low),
                new BigDecimal(close),
                new BigDecimal(volume),
                false);
    }

    private static OfficialMarketSession session(String opensAt, String closesAt) {
        return new OfficialMarketSession(
                LocalDate.parse(opensAt.substring(0, 10)), Instant.parse(opensAt), Instant.parse(closesAt));
    }
}
