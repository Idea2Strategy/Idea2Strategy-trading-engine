package com.idea2strategy.trading.market.candle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.idea2strategy.trading.market.session.OfficialMarketSession;
import com.idea2strategy.trading.messaging.market.MarketCandle;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AlpacaThirtyMinuteBarsJsonParserTest {
    @Test
    void parsesOnlyFinalizedThirtyMinuteBarsThroughTheRequestedBoundary() {
        UUID instrument = UUID.fromString("68ed5d6c-7472-44d7-a606-bb1d32726d80");
        OfficialMarketSession session = new OfficialMarketSession(
                LocalDate.parse("2026-08-03"),
                Instant.parse("2026-08-03T13:30:00Z"),
                Instant.parse("2026-08-03T20:00:00Z"));
        String body = "{\"bars\":{\"AAPL\":["
                + "{\"t\":\"2026-08-03T13:30:00Z\",\"o\":100,\"h\":102,\"l\":99,\"c\":101,\"v\":10},"
                + "{\"t\":\"2026-08-03T14:00:00Z\",\"o\":101,\"h\":104,\"l\":100,\"c\":103,\"v\":20},"
                + "{\"t\":\"2026-08-03T14:30:00Z\",\"o\":103,\"h\":105,\"l\":102,\"c\":104,\"v\":30}"
                + "]},\"next_page_token\":null}";

        List<MarketCandle> bars = new AlpacaThirtyMinuteBarsJsonParser()
                .parse(body, Map.of("AAPL", instrument), session, Instant.parse("2026-08-03T14:30:00Z"))
                .get("AAPL");

        assertEquals(2, bars.size());
        assertEquals(new BigDecimal("103"), bars.getLast().close());
        assertEquals(Instant.parse("2026-08-03T14:30:00Z"), bars.getLast().closesAt());
    }
}
