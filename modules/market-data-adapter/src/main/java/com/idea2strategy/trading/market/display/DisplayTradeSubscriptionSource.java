package com.idea2strategy.trading.market.display;

import java.time.Instant;
import java.util.Set;

@FunctionalInterface
public interface DisplayTradeSubscriptionSource {
    Set<String> desiredSymbols(Instant now);
}
