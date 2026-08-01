package com.idea2strategy.trading.market.redis;

import java.time.Duration;
import java.util.Objects;

public record ConsumerLagMeasurement(
        long entryLag,
        Duration observationTimeLag,
        String lastDeliveredStreamId) {

    public ConsumerLagMeasurement {
        if (entryLag < 0) {
            throw new IllegalArgumentException("entryLag must not be negative");
        }
        observationTimeLag = Objects.requireNonNull(observationTimeLag, "observationTimeLag");
        if (observationTimeLag.isNegative()) {
            throw new IllegalArgumentException("observationTimeLag must not be negative");
        }
        lastDeliveredStreamId = Objects.requireNonNull(lastDeliveredStreamId, "lastDeliveredStreamId");
    }
}
