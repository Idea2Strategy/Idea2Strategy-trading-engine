package com.idea2strategy.trading.market.session;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

public record OfficialMarketSession(
        LocalDate tradingDate,
        Instant opensAt,
        Instant closesAt) {

    public OfficialMarketSession {
        tradingDate = Objects.requireNonNull(tradingDate, "tradingDate");
        opensAt = Objects.requireNonNull(opensAt, "opensAt");
        closesAt = Objects.requireNonNull(closesAt, "closesAt");
        if (!opensAt.isBefore(closesAt)) {
            throw new IllegalArgumentException("opensAt must precede closesAt");
        }
    }
}
