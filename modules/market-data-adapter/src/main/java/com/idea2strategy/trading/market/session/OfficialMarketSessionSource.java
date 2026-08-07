package com.idea2strategy.trading.market.session;

import java.time.LocalDate;
import java.util.Optional;

@FunctionalInterface
public interface OfficialMarketSessionSource {
    Optional<OfficialMarketSession> session(LocalDate tradingDate);
}
