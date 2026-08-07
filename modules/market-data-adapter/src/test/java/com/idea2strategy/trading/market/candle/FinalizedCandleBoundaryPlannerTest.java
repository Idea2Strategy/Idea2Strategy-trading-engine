package com.idea2strategy.trading.market.candle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.idea2strategy.trading.market.session.OfficialMarketSession;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class FinalizedCandleBoundaryPlannerTest {
    @Test
    void waitsForGraceAndStopsAtTheOfficialEarlyClose() {
        OfficialMarketSession session = new OfficialMarketSession(
                LocalDate.parse("2026-11-27"),
                Instant.parse("2026-11-27T14:30:00Z"),
                Instant.parse("2026-11-27T18:00:00Z"));
        FinalizedCandleBoundaryPlanner planner = new FinalizedCandleBoundaryPlanner();

        assertEquals(List.of(), planner.readyBoundaries(
                session, Instant.parse("2026-11-27T15:00:01Z"), Duration.ofSeconds(2)));
        List<Instant> all = planner.readyBoundaries(
                session, Instant.parse("2026-11-27T20:00:00Z"), Duration.ofSeconds(2));

        assertEquals(7, all.size());
        assertEquals(session.closesAt(), all.getLast());
    }
}
