package com.idea2strategy.trading.market.session;

import java.util.Objects;
import java.util.Optional;

public record MarketSessionAssessment(
        MarketSessionStatus status,
        boolean tradable,
        String reason,
        Optional<OfficialMarketSession> session) {

    public MarketSessionAssessment {
        status = Objects.requireNonNull(status, "status");
        reason = requireText(reason, "reason");
        session = Objects.requireNonNull(session, "session");
        if (tradable != (status == MarketSessionStatus.REGULAR_OPEN)) {
            throw new IllegalArgumentException("only REGULAR_OPEN can be tradable");
        }
    }

    static MarketSessionAssessment unavailable(String reason) {
        return new MarketSessionAssessment(
                MarketSessionStatus.CALENDAR_UNAVAILABLE,
                false,
                reason,
                Optional.empty());
    }

    static MarketSessionAssessment closed(String reason) {
        return new MarketSessionAssessment(
                MarketSessionStatus.MARKET_CLOSED,
                false,
                reason,
                Optional.empty());
    }

    static MarketSessionAssessment forSession(
            MarketSessionStatus status,
            OfficialMarketSession session,
            String reason) {
        return new MarketSessionAssessment(
                status,
                status == MarketSessionStatus.REGULAR_OPEN,
                reason,
                Optional.of(session));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
