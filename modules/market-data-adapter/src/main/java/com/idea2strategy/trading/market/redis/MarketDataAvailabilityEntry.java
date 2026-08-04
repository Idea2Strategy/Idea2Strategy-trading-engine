package com.idea2strategy.trading.market.redis;

import com.idea2strategy.trading.market.availability.MarketDataAvailabilityProjection;
import com.idea2strategy.trading.market.availability.MarketDataAvailabilityStatus;
import com.idea2strategy.trading.market.availability.MarketDataDegradationReason;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Shared decoder for the gateway-owned availability hash read by trading workers. */
public final class MarketDataAvailabilityEntry {
    private MarketDataAvailabilityEntry() {}

    public static MarketDataAvailabilityProjection decode(Map<String, String> fields) {
        try {
            String encodedReasons = required(fields, "reasons");
            Set<MarketDataDegradationReason> reasons = encodedReasons.isBlank()
                    ? Set.of()
                    : Arrays.stream(encodedReasons.split(","))
                            .map(MarketDataDegradationReason::valueOf)
                            .collect(Collectors.toUnmodifiableSet());
            return new MarketDataAvailabilityProjection(
                    Integer.parseInt(required(fields, "schemaVersion")),
                    UUID.fromString(required(fields, "instrumentId")),
                    Long.parseLong(required(fields, "marketSequence")),
                    Instant.parse(required(fields, "observedAt")),
                    MarketDataAvailabilityStatus.valueOf(required(fields, "status")),
                    Boolean.parseBoolean(required(fields, "evaluationAllowed")),
                    reasons);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("market availability projection is malformed", failure);
        }
    }

    private static String required(Map<String, String> fields, String name) {
        String value = fields.get(name);
        if (value == null) {
            throw new IllegalStateException("market availability projection is missing " + name);
        }
        return value;
    }
}
