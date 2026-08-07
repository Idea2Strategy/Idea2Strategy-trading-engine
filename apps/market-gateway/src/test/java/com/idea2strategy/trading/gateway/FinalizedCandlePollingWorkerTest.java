package com.idea2strategy.trading.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.idea2strategy.trading.market.candle.AlpacaThirtyMinuteBarsClient;
import com.idea2strategy.trading.market.candle.FinalizedCandleCycle;
import com.idea2strategy.trading.market.session.OfficialMarketSession;
import com.idea2strategy.trading.messaging.market.MarketCandle;
import com.idea2strategy.trading.messaging.market.MarketEventEnvelope;
import com.idea2strategy.trading.messaging.market.MarketTimeframe;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FinalizedCandlePollingWorkerTest {
    private static final UUID AAPL = UUID.fromString("c7000000-0000-4000-8000-000000000001");
    private static final UUID MSFT = UUID.fromString("c7000000-0000-4000-8000-000000000002");

    @Test
    void retriesOnlyMissingSymbolsUntilTheBoundaryIsComplete() {
        OfficialMarketSession session = new OfficialMarketSession(
                LocalDate.parse("2026-08-03"),
                Instant.parse("2026-08-03T13:30:00Z"),
                Instant.parse("2026-08-03T20:00:00Z"));
        Instant boundary = Instant.parse("2026-08-03T14:00:00Z");
        Clock clock = Clock.fixed(boundary.plusSeconds(2), ZoneOffset.UTC);
        List<Set<String>> requests = new ArrayList<>();
        List<MarketEventEnvelope> events = new ArrayList<>();
        AlpacaThirtyMinuteBarsClient client = (instruments, ignoredSession, ignoredBoundary) -> {
            requests.add(Set.copyOf(instruments.keySet()));
            Map<String, List<MarketCandle>> result = new LinkedHashMap<>();
            if (instruments.containsKey("AAPL")) {
                result.put("AAPL", List.of(candle(AAPL, session.opensAt(), "100")));
            }
            if (instruments.containsKey("MSFT")) {
                result.put("MSFT", requests.size() == 1
                        ? List.of()
                        : List.of(candle(MSFT, session.opensAt(), "200")));
            }
            return result;
        };
        FinalizedCandleCycle cycle = new FinalizedCandleCycle(client, events::add, clock, 200);
        FinalizedCandlePollingWorker worker = new FinalizedCandlePollingWorker(
                cycle,
                new ApprovedInstruments(Map.of("AAPL", AAPL, "MSFT", MSFT)),
                date -> java.util.Optional.of(session),
                clock,
                Duration.ofSeconds(2));

        worker.poll();
        worker.poll();
        worker.poll();

        assertEquals(List.of(Set.of("AAPL", "MSFT"), Set.of("MSFT")), requests);
        assertEquals(4, events.size());
    }

    private static MarketCandle candle(UUID instrumentId, Instant opensAt, String close) {
        BigDecimal price = new BigDecimal(close);
        return new MarketCandle(
                instrumentId,
                MarketTimeframe.THIRTY_MINUTES,
                opensAt,
                opensAt.plus(Duration.ofMinutes(30)),
                price,
                price,
                price,
                price,
                BigDecimal.TEN,
                false);
    }
}
