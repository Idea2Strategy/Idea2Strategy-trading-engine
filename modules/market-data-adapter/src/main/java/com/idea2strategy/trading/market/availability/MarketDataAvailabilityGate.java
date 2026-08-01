package com.idea2strategy.trading.market.availability;

import com.idea2strategy.trading.market.alpaca.MarketStreamStatus;
import com.idea2strategy.trading.market.session.MarketSessionStatus;
import java.time.Duration;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class MarketDataAvailabilityGate {
    private final long maximumEntryLag;
    private final Duration maximumObservationLag;
    private final Map<String, Set<MarketDataDegradationReason>> degradationBySymbol = new HashMap<>();

    public MarketDataAvailabilityGate(long maximumEntryLag, Duration maximumObservationLag) {
        if (maximumEntryLag < 0) {
            throw new IllegalArgumentException("maximumEntryLag must not be negative");
        }
        this.maximumEntryLag = maximumEntryLag;
        this.maximumObservationLag = Objects.requireNonNull(maximumObservationLag, "maximumObservationLag");
        if (maximumObservationLag.isNegative()) {
            throw new IllegalArgumentException("maximumObservationLag must not be negative");
        }
    }

    public synchronized MarketDataAvailabilityResult evaluate(MarketDataAvailabilityInput input) {
        Objects.requireNonNull(input, "input");
        MarketSessionStatus sessionStatus = input.sessionAssessment().status();

        if (sessionStatus != MarketSessionStatus.REGULAR_OPEN
                && sessionStatus != MarketSessionStatus.CALENDAR_UNAVAILABLE) {
            return result(MarketDataAvailabilityStatus.MARKET_CLOSED, Set.of(), List.of());
        }

        Set<MarketDataDegradationReason> reasons = degradationReasons(input);
        Set<MarketDataDegradationReason> previous = degradationBySymbol.get(input.symbol());

        if (!reasons.isEmpty()) {
            degradationBySymbol.put(input.symbol(), Set.copyOf(reasons));
            List<MarketDataAvailabilityEvent> events = previous == null
                    ? List.of(event(MarketDataAvailabilityEventType.DATA_DEGRADED, input, reasons))
                    : List.of();
            return result(MarketDataAvailabilityStatus.DEGRADED, reasons, events);
        }

        degradationBySymbol.remove(input.symbol());
        List<MarketDataAvailabilityEvent> events = previous == null
                ? List.of()
                : List.of(event(MarketDataAvailabilityEventType.DATA_RECOVERED, input, previous));
        return result(MarketDataAvailabilityStatus.AVAILABLE, Set.of(), events);
    }

    private Set<MarketDataDegradationReason> degradationReasons(MarketDataAvailabilityInput input) {
        EnumSet<MarketDataDegradationReason> reasons = EnumSet.noneOf(MarketDataDegradationReason.class);
        if (!input.providerConnected()) {
            reasons.add(MarketDataDegradationReason.PROVIDER_DISCONNECTED);
        }
        if (input.streamStatus() == MarketStreamStatus.STALE) {
            reasons.add(MarketDataDegradationReason.STREAM_STALE);
        } else if (input.streamStatus() == MarketStreamStatus.GAP) {
            reasons.add(MarketDataDegradationReason.SEQUENCE_GAP);
        }
        if (input.consumerLag().entryLag() > maximumEntryLag
                || input.consumerLag().observationTimeLag().compareTo(maximumObservationLag) > 0) {
            reasons.add(MarketDataDegradationReason.CONSUMER_LAG);
        }
        if (input.sessionAssessment().status() == MarketSessionStatus.CALENDAR_UNAVAILABLE) {
            reasons.add(MarketDataDegradationReason.CALENDAR_UNAVAILABLE);
        }
        return reasons;
    }

    private static MarketDataAvailabilityEvent event(
            MarketDataAvailabilityEventType type,
            MarketDataAvailabilityInput input,
            Set<MarketDataDegradationReason> reasons) {
        return new MarketDataAvailabilityEvent(type, input.symbol(), input.observedAt(), reasons);
    }

    private static MarketDataAvailabilityResult result(
            MarketDataAvailabilityStatus status,
            Set<MarketDataDegradationReason> reasons,
            List<MarketDataAvailabilityEvent> events) {
        boolean available = status == MarketDataAvailabilityStatus.AVAILABLE;
        return new MarketDataAvailabilityResult(status, available, available, reasons, events);
    }
}
