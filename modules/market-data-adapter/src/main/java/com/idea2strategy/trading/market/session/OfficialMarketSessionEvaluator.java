package com.idea2strategy.trading.market.session;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class OfficialMarketSessionEvaluator {
    public static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private final Duration maximumSnapshotAge;

    public OfficialMarketSessionEvaluator(Duration maximumSnapshotAge) {
        this.maximumSnapshotAge = Objects.requireNonNull(maximumSnapshotAge, "maximumSnapshotAge");
        if (maximumSnapshotAge.isNegative() || maximumSnapshotAge.isZero()) {
            throw new IllegalArgumentException("maximumSnapshotAge must be positive");
        }
    }

    public MarketSessionAssessment assess(
            OfficialMarketCalendarSnapshot snapshot,
            Instant assessedAt) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(assessedAt, "assessedAt");

        if (assessedAt.isBefore(snapshot.fetchedAt())) {
            return MarketSessionAssessment.unavailable("calendar snapshot is from the future");
        }
        if (Duration.between(snapshot.fetchedAt(), assessedAt).compareTo(maximumSnapshotAge) > 0) {
            return MarketSessionAssessment.unavailable("calendar snapshot is stale");
        }

        LocalDate marketDate = assessedAt.atZone(NEW_YORK).toLocalDate();
        if (marketDate.isBefore(snapshot.coveredFrom()) || marketDate.isAfter(snapshot.coveredThrough())) {
            return MarketSessionAssessment.unavailable("calendar does not cover the assessed market date");
        }

        Map<LocalDate, OfficialMarketSession> byDate = new HashMap<>();
        for (OfficialMarketSession session : snapshot.sessions()) {
            if (isContradictory(snapshot, session) || byDate.putIfAbsent(session.tradingDate(), session) != null) {
                return MarketSessionAssessment.unavailable("calendar contains contradictory sessions");
            }
        }

        OfficialMarketSession session = byDate.get(marketDate);
        if (session == null) {
            return MarketSessionAssessment.closed("official calendar has no session for the covered date");
        }
        if (assessedAt.isBefore(session.opensAt())) {
            return MarketSessionAssessment.forSession(
                    MarketSessionStatus.PRE_MARKET,
                    session,
                    "regular session has not opened");
        }
        if (assessedAt.isBefore(session.closesAt())) {
            return MarketSessionAssessment.forSession(
                    MarketSessionStatus.REGULAR_OPEN,
                    session,
                    "inside official regular session");
        }
        return MarketSessionAssessment.forSession(
                MarketSessionStatus.POST_MARKET,
                session,
                "official regular session has closed");
    }

    private static boolean isContradictory(
            OfficialMarketCalendarSnapshot snapshot,
            OfficialMarketSession session) {
        if (session.tradingDate().isBefore(snapshot.coveredFrom())
                || session.tradingDate().isAfter(snapshot.coveredThrough())) {
            return true;
        }
        return !session.opensAt().atZone(NEW_YORK).toLocalDate().equals(session.tradingDate())
                || !session.closesAt().atZone(NEW_YORK).toLocalDate().equals(session.tradingDate());
    }
}
