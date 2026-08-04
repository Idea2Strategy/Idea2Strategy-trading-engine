package com.idea2strategy.trading.market.availability;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** The gateway-owned C09 decision exposed to workers without leaking symbol mappings. */
public record MarketDataAvailabilityProjection(
        int schemaVersion,
        UUID instrumentId,
        long marketSequence,
        Instant observedAt,
        MarketDataAvailabilityStatus status,
        boolean evaluationAllowed,
        Set<MarketDataDegradationReason> reasons) {

    public static final int SCHEMA_VERSION = 1;

    public MarketDataAvailabilityProjection {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported availability schemaVersion: " + schemaVersion);
        }
        instrumentId = Objects.requireNonNull(instrumentId, "instrumentId");
        if (marketSequence < 0) {
            throw new IllegalArgumentException("marketSequence must not be negative");
        }
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        status = Objects.requireNonNull(status, "status");
        reasons = Set.copyOf(Objects.requireNonNull(reasons, "reasons"));
        if (evaluationAllowed != (status == MarketDataAvailabilityStatus.AVAILABLE)) {
            throw new IllegalArgumentException("only AVAILABLE permits evaluation");
        }
        if ((status == MarketDataAvailabilityStatus.DEGRADED) == reasons.isEmpty()) {
            throw new IllegalArgumentException("only DEGRADED projections must have reasons");
        }
    }

    public static MarketDataAvailabilityProjection from(
            UUID instrumentId,
            long marketSequence,
            Instant observedAt,
            MarketDataAvailabilityResult result) {
        Objects.requireNonNull(result, "result");
        return new MarketDataAvailabilityProjection(
                SCHEMA_VERSION,
                instrumentId,
                marketSequence,
                observedAt,
                result.status(),
                result.evaluationAllowed(),
                result.reasons());
    }
}
