package com.idea2strategy.trading.market.availability;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public record MarketDataAvailabilityResult(
        MarketDataAvailabilityStatus status,
        boolean evaluationAllowed,
        boolean orderCandidateAllowed,
        Set<MarketDataDegradationReason> reasons,
        List<MarketDataAvailabilityEvent> events) {

    public MarketDataAvailabilityResult {
        status = Objects.requireNonNull(status, "status");
        reasons = Set.copyOf(Objects.requireNonNull(reasons, "reasons"));
        events = List.copyOf(Objects.requireNonNull(events, "events"));

        boolean available = status == MarketDataAvailabilityStatus.AVAILABLE;
        if (evaluationAllowed != available || orderCandidateAllowed != available) {
            throw new IllegalArgumentException("only AVAILABLE data can be evaluated or produce order candidates");
        }
        if ((status == MarketDataAvailabilityStatus.DEGRADED) == reasons.isEmpty()) {
            throw new IllegalArgumentException("only DEGRADED results must have reasons");
        }
    }
}
