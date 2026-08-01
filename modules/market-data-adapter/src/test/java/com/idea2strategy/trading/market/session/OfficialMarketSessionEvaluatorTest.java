package com.idea2strategy.trading.market.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class OfficialMarketSessionEvaluatorTest {
    private static final OfficialMarketSessionEvaluator EVALUATOR =
            new OfficialMarketSessionEvaluator(Duration.ofHours(6));

    @Test
    void usesTheOfficialEarlyCloseAsTheExclusiveTradingBoundary() {
        OfficialMarketCalendarSnapshot snapshot = snapshot(
                LocalDate.parse("2025-11-28"),
                LocalDate.parse("2025-11-28"),
                Instant.parse("2025-11-28T16:00:00Z"),
                List.of(session("2025-11-28", "2025-11-28T14:30:00Z", "2025-11-28T18:00:00Z")));

        MarketSessionAssessment beforeClose =
                EVALUATOR.assess(snapshot, Instant.parse("2025-11-28T17:59:59Z"));
        MarketSessionAssessment atClose =
                EVALUATOR.assess(snapshot, Instant.parse("2025-11-28T18:00:00Z"));

        assertEquals(MarketSessionStatus.REGULAR_OPEN, beforeClose.status());
        assertTrue(beforeClose.tradable());
        assertEquals(MarketSessionStatus.POST_MARKET, atClose.status());
        assertFalse(atClose.tradable());
    }

    @Test
    void convertsOfficialLocalTimesWithNewYorkDaylightSavingRules() {
        AlpacaCalendarJsonParser parser = new AlpacaCalendarJsonParser();

        OfficialMarketCalendarSnapshot winter = parser.parse(
                "[{\"date\":\"2026-01-05\",\"open\":\"09:30\",\"close\":\"16:00\"}]",
                LocalDate.parse("2026-01-05"),
                LocalDate.parse("2026-01-05"),
                Instant.parse("2026-01-05T12:00:00Z"));
        OfficialMarketCalendarSnapshot summer = parser.parse(
                "[{\"date\":\"2026-07-06\",\"open\":\"09:30\",\"close\":\"16:00\"}]",
                LocalDate.parse("2026-07-06"),
                LocalDate.parse("2026-07-06"),
                Instant.parse("2026-07-06T12:00:00Z"));

        assertEquals(Instant.parse("2026-01-05T14:30:00Z"), winter.sessions().getFirst().opensAt());
        assertEquals(Instant.parse("2026-07-06T13:30:00Z"), summer.sessions().getFirst().opensAt());
    }

    @Test
    void treatsCoveredDatesWithoutSessionsAsClosedMarketDays() {
        OfficialMarketCalendarSnapshot snapshot = snapshot(
                LocalDate.parse("2026-07-03"),
                LocalDate.parse("2026-07-05"),
                Instant.parse("2026-07-04T14:00:00Z"),
                List.of());

        MarketSessionAssessment assessment =
                EVALUATOR.assess(snapshot, Instant.parse("2026-07-04T15:00:00Z"));

        assertEquals(MarketSessionStatus.MARKET_CLOSED, assessment.status());
        assertFalse(assessment.tradable());
    }

    @Test
    void failsClosedForStaleOutOfCoverageOrDuplicateCalendarData() {
        OfficialMarketCalendarSnapshot stale = snapshot(
                LocalDate.parse("2026-08-03"),
                LocalDate.parse("2026-08-03"),
                Instant.parse("2026-08-03T00:00:00Z"),
                List.of(session("2026-08-03", "2026-08-03T13:30:00Z", "2026-08-03T20:00:00Z")));
        OfficialMarketSession duplicate =
                session("2026-08-03", "2026-08-03T13:30:00Z", "2026-08-03T20:00:00Z");
        OfficialMarketCalendarSnapshot contradictory = snapshot(
                LocalDate.parse("2026-08-03"),
                LocalDate.parse("2026-08-03"),
                Instant.parse("2026-08-03T12:00:00Z"),
                List.of(duplicate, duplicate));

        assertEquals(
                MarketSessionStatus.CALENDAR_UNAVAILABLE,
                EVALUATOR.assess(stale, Instant.parse("2026-08-03T13:30:00Z")).status());
        assertEquals(
                MarketSessionStatus.CALENDAR_UNAVAILABLE,
                EVALUATOR.assess(stale, Instant.parse("2026-08-04T13:30:00Z")).status());
        assertEquals(
                MarketSessionStatus.CALENDAR_UNAVAILABLE,
                EVALUATOR.assess(contradictory, Instant.parse("2026-08-03T13:30:00Z")).status());
    }

    @Test
    void distinguishesPreMarketFromTheInclusiveRegularOpen() {
        OfficialMarketCalendarSnapshot snapshot = snapshot(
                LocalDate.parse("2026-08-03"),
                LocalDate.parse("2026-08-03"),
                Instant.parse("2026-08-03T12:00:00Z"),
                List.of(session("2026-08-03", "2026-08-03T13:30:00Z", "2026-08-03T20:00:00Z")));

        assertEquals(
                MarketSessionStatus.PRE_MARKET,
                EVALUATOR.assess(snapshot, Instant.parse("2026-08-03T13:29:59Z")).status());
        assertEquals(
                MarketSessionStatus.REGULAR_OPEN,
                EVALUATOR.assess(snapshot, Instant.parse("2026-08-03T13:30:00Z")).status());
    }

    @Test
    void acceptsOffsetTimestampsAndIgnoresOptionalProviderFields() {
        OfficialMarketCalendarSnapshot snapshot = new AlpacaCalendarJsonParser().parse(
                "[{\"date\":\"2026-08-03\",\"open\":\"2026-08-03T09:30:00-04:00\","
                        + "\"close\":\"2026-08-03T16:00:00-04:00\",\"settlement_date\":\"2026-08-05\"}]",
                LocalDate.parse("2026-08-03"),
                LocalDate.parse("2026-08-03"),
                Instant.parse("2026-08-03T12:00:00Z"));

        assertEquals(Instant.parse("2026-08-03T13:30:00Z"), snapshot.sessions().getFirst().opensAt());
        assertEquals(Instant.parse("2026-08-03T20:00:00Z"), snapshot.sessions().getFirst().closesAt());
    }

    @Test
    void rejectsMalformedProviderCalendarEntries() {
        AlpacaCalendarJsonParser parser = new AlpacaCalendarJsonParser();

        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse(
                        "{\"date\":\"2026-08-03\"}",
                        LocalDate.parse("2026-08-03"),
                        LocalDate.parse("2026-08-03"),
                        Instant.parse("2026-08-03T12:00:00Z")));
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse(
                        "[{\"date\":\"2026-08-03\",\"open\":\"09:30\"}]",
                        LocalDate.parse("2026-08-03"),
                        LocalDate.parse("2026-08-03"),
                        Instant.parse("2026-08-03T12:00:00Z")));
    }

    @Test
    void failsClosedWhenTheSnapshotTimestampIsInTheFuture() {
        OfficialMarketCalendarSnapshot snapshot = snapshot(
                LocalDate.parse("2026-08-03"),
                LocalDate.parse("2026-08-03"),
                Instant.parse("2026-08-03T14:00:01Z"),
                List.of(session("2026-08-03", "2026-08-03T13:30:00Z", "2026-08-03T20:00:00Z")));

        MarketSessionAssessment assessment =
                EVALUATOR.assess(snapshot, Instant.parse("2026-08-03T14:00:00Z"));

        assertEquals(MarketSessionStatus.CALENDAR_UNAVAILABLE, assessment.status());
        assertFalse(assessment.tradable());
    }

    private static OfficialMarketCalendarSnapshot snapshot(
            LocalDate coveredFrom,
            LocalDate coveredThrough,
            Instant fetchedAt,
            List<OfficialMarketSession> sessions) {
        return new OfficialMarketCalendarSnapshot(
                "alpaca",
                "US_EQUITIES",
                coveredFrom,
                coveredThrough,
                fetchedAt,
                sessions);
    }

    private static OfficialMarketSession session(String date, String opensAt, String closesAt) {
        return new OfficialMarketSession(
                LocalDate.parse(date),
                Instant.parse(opensAt),
                Instant.parse(closesAt));
    }
}
