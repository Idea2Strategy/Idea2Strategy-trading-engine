package com.idea2strategy.trading.market.session;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public record OfficialMarketCalendarSnapshot(
        String provider,
        String market,
        LocalDate coveredFrom,
        LocalDate coveredThrough,
        Instant fetchedAt,
        List<OfficialMarketSession> sessions) {

    public OfficialMarketCalendarSnapshot {
        provider = requireText(provider, "provider");
        market = requireText(market, "market");
        coveredFrom = Objects.requireNonNull(coveredFrom, "coveredFrom");
        coveredThrough = Objects.requireNonNull(coveredThrough, "coveredThrough");
        fetchedAt = Objects.requireNonNull(fetchedAt, "fetchedAt");
        sessions = List.copyOf(Objects.requireNonNull(sessions, "sessions"));
        if (coveredFrom.isAfter(coveredThrough)) {
            throw new IllegalArgumentException("coveredFrom must not follow coveredThrough");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
