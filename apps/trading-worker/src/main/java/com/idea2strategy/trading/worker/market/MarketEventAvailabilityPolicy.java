package com.idea2strategy.trading.worker.market;

import com.idea2strategy.trading.messaging.market.MarketEventEnvelope;

@FunctionalInterface
public interface MarketEventAvailabilityPolicy {
    boolean permits(MarketEventEnvelope event);
}
