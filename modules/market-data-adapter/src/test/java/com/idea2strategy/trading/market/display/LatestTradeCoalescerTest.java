package com.idea2strategy.trading.market.display;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.idea2strategy.trading.market.alpaca.AlpacaSipInboundMessage;
import com.idea2strategy.trading.messaging.market.DisplayPriceUpdate;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LatestTradeCoalescerTest {
    @Test
    void preservesIntervalOhlcvWhileEmittingTheLatestTrade() {
        UUID instrument = UUID.fromString("68ed5d6c-7472-44d7-a606-bb1d32726d80");
        Instant publishedAt = Instant.parse("2026-08-03T14:30:00.250Z");
        LatestTradeCoalescer coalescer = new LatestTradeCoalescer(
                Map.of("AAPL", instrument), Clock.fixed(publishedAt, ZoneOffset.UTC));
        coalescer.accept(tick(1, "100.00", "2", "2026-08-03T14:30:00.010Z"));
        coalescer.accept(tick(2, "101.50", "3", "2026-08-03T14:30:00.080Z"));
        coalescer.accept(tick(3, "99.75", "4", "2026-08-03T14:30:00.120Z"));

        List<DisplayPriceUpdate> first = coalescer.flush();

        assertEquals(1, first.size());
        assertEquals(new BigDecimal("99.75"), first.getFirst().price());
        assertEquals(new BigDecimal("100.00"), first.getFirst().intervalOpen());
        assertEquals(new BigDecimal("101.50"), first.getFirst().intervalHigh());
        assertEquals(new BigDecimal("99.75"), first.getFirst().intervalLow());
        assertEquals(new BigDecimal("99.75"), first.getFirst().intervalClose());
        assertEquals(new BigDecimal("9"), first.getFirst().intervalVolume());
        assertEquals(3, first.getFirst().intervalTradeCount());
        assertEquals(List.of(), coalescer.flush());
    }

    @Test
    void choosesIntervalOpenAndCloseByProviderTimeWhenTicksArriveOutOfOrder() {
        UUID instrument = UUID.fromString("68ed5d6c-7472-44d7-a606-bb1d32726d80");
        LatestTradeCoalescer coalescer = new LatestTradeCoalescer(
                Map.of("AAPL", instrument),
                Clock.fixed(Instant.parse("2026-08-03T14:30:00.250Z"), ZoneOffset.UTC));
        coalescer.accept(tick(3, "101.00", "1", "2026-08-03T14:30:00.120Z"));
        coalescer.accept(tick(1, "99.50", "1", "2026-08-03T14:30:00.010Z"));
        coalescer.accept(tick(2, "102.00", "1", "2026-08-03T14:30:00.080Z"));

        DisplayPriceUpdate update = coalescer.flush().getFirst();

        assertEquals(new BigDecimal("99.50"), update.intervalOpen());
        assertEquals(new BigDecimal("102.00"), update.intervalHigh());
        assertEquals(new BigDecimal("99.50"), update.intervalLow());
        assertEquals(new BigDecimal("101.00"), update.intervalClose());
    }

    private static AlpacaSipInboundMessage.TradeTick tick(
            long id, String price, String size, String occurredAt) {
        Instant at = Instant.parse(occurredAt);
        return new AlpacaSipInboundMessage.TradeTick(
                "AAPL", id, "V", new BigDecimal(price), new BigDecimal(size), at, at,
                List.of("@"), "C");
    }
}
