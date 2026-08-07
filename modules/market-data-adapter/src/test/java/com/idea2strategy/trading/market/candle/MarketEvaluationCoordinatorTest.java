package com.idea2strategy.trading.market.candle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.idea2strategy.trading.messaging.market.MarketCandle;
import com.idea2strategy.trading.messaging.market.MarketEventEnvelope;
import com.idea2strategy.trading.messaging.market.MarketEventType;
import com.idea2strategy.trading.messaging.market.MarketTimeframe;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MarketEvaluationCoordinatorTest {
    private static final UUID INSTRUMENT = UUID.fromString("68ed5d6c-7472-44d7-a606-bb1d32726d80");

    @Test
    void collapsesAllTimeframesClosedAtOneBoundaryIntoOneDeterministicEvent() {
        Instant boundary = Instant.parse("2026-08-03T17:30:00Z");
        List<MarketCandle> closed = List.of(
                candle(MarketTimeframe.THIRTY_MINUTES, boundary.minusSeconds(1800), boundary, false),
                candle(MarketTimeframe.ONE_HOUR, boundary.minusSeconds(3600), boundary, false),
                candle(MarketTimeframe.FOUR_HOURS, boundary.minusSeconds(14400), boundary, false));
        MarketEvaluationCoordinator coordinator = new MarketEvaluationCoordinator();

        MarketEventEnvelope first = coordinator.ready(INSTRUMENT, boundary, closed, boundary.plusMillis(50));
        MarketEventEnvelope redelivery = coordinator.ready(INSTRUMENT, boundary, closed, boundary.plusSeconds(2));

        assertEquals(MarketEventType.MARKET_EVALUATION_READY, first.eventType());
        assertEquals(first.eventId(), redelivery.eventId());
        assertEquals(BigDecimal.ONE, first.values().get("closed30m"));
        assertEquals(BigDecimal.ONE, first.values().get("closed1h"));
        assertEquals(BigDecimal.ONE, first.values().get("closed4h"));
        assertEquals(BigDecimal.ZERO, first.values().get("closed1d"));
        assertEquals(new BigDecimal("103"), first.values().get("close"));
        assertEquals(new BigDecimal("100"), first.values().get("open30m"));
        assertEquals(new BigDecimal("104"), first.values().get("high1h"));
        assertEquals(new BigDecimal("30"), first.values().get("volume4h"));
        assertFalse(first.values().containsKey("open1d"));
    }

    private static MarketCandle candle(
            MarketTimeframe timeframe, Instant opensAt, Instant closesAt, boolean partial) {
        return new MarketCandle(
                INSTRUMENT, timeframe, opensAt, closesAt,
                new BigDecimal("100"), new BigDecimal("104"), new BigDecimal("99"),
                new BigDecimal("103"), new BigDecimal("30"), partial);
    }
}
