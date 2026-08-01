package com.idea2strategy.trading.market.alpaca;

import com.idea2strategy.trading.messaging.market.MarketEventEnvelope;
import java.util.Objects;

public record MarketEventHandlingResult(
        MarketEventHandlingStatus status,
        MarketEventEnvelope event,
        long latestAppliedSequence,
        boolean shouldPublish,
        boolean shouldUpdateLatestValue) {

    public MarketEventHandlingResult {
        status = Objects.requireNonNull(status, "status");
        event = Objects.requireNonNull(event, "event");
        if (latestAppliedSequence < -1) {
            throw new IllegalArgumentException("latestAppliedSequence must be -1 or greater");
        }
        if (shouldUpdateLatestValue && !shouldPublish) {
            throw new IllegalArgumentException("latest value cannot update without publishing the event");
        }
    }
}
