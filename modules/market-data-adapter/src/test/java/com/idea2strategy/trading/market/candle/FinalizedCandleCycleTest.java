package com.idea2strategy.trading.market.candle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.idea2strategy.trading.market.session.OfficialMarketSession;
import com.idea2strategy.trading.messaging.market.MarketCandle;
import com.idea2strategy.trading.messaging.market.MarketEventEnvelope;
import com.idea2strategy.trading.messaging.market.MarketEventType;
import com.idea2strategy.trading.messaging.market.MarketTimeframe;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FinalizedCandleCycleTest {
    private static final UUID AAPL = UUID.fromString("68ed5d6c-7472-44d7-a606-bb1d32726d80");
    private static final UUID MSFT = UUID.fromString("7a06416a-facf-4e60-a453-9350f729ff06");

    @Test
    void emitsCandlesThenExactlyOneEvaluationEventAndSkipsMissingSymbols() {
        OfficialMarketSession session = new OfficialMarketSession(
                LocalDate.parse("2026-08-03"),
                Instant.parse("2026-08-03T13:30:00Z"),
                Instant.parse("2026-08-03T20:00:00Z"));
        Instant boundary = Instant.parse("2026-08-03T14:30:00Z");
        List<MarketEventEnvelope> events = new ArrayList<>();
        AlpacaThirtyMinuteBarsClient client = (instruments, ignoredSession, ignoredBoundary) -> Map.of(
                "AAPL", List.of(
                        candle(AAPL, session.opensAt(), "100"),
                        candle(AAPL, session.opensAt().plusSeconds(1800), "101")),
                "MSFT", List.of());
        FinalizedCandleCycle cycle = new FinalizedCandleCycle(
                client,
                events::add,
                Clock.fixed(boundary.plusMillis(250), ZoneOffset.UTC),
                200);

        FinalizedCandleCycle.FinalizedCandleCycleResult result = cycle.run(
                Map.of("AAPL", AAPL, "MSFT", MSFT), session, boundary);

        assertEquals(1, result.evaluatedInstrumentCount());
        assertEquals(1, result.missingInstrumentCount());
        assertEquals(java.util.Set.of("MSFT"), result.missingSymbols());
        assertEquals(
                List.of(MarketEventType.BAR_30M, MarketEventType.BAR_1H, MarketEventType.MARKET_EVALUATION_READY),
                events.stream().map(MarketEventEnvelope::eventType).toList());
    }

    private static MarketCandle candle(UUID instrument, Instant opensAt, String close) {
        BigDecimal price = new BigDecimal(close);
        return new MarketCandle(
                instrument, MarketTimeframe.THIRTY_MINUTES, opensAt, opensAt.plusSeconds(1800),
                price, price, price, price, BigDecimal.TEN, false);
    }
}
