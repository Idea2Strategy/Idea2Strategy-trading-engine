package com.idea2strategy.trading.messaging.market;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

public record MarketEventEnvelope(
        String eventId,
        int schemaVersion,
        UUID instrumentId,
        String provider,
        String feed,
        MarketEventType eventType,
        String providerEventId,
        Instant occurredAt,
        Instant receivedAt,
        long sequence,
        int revision,
        String correctionOfEventId,
        Map<String, BigDecimal> values) {

    public MarketEventEnvelope {
        eventId = requireText(eventId, "eventId");
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        instrumentId = Objects.requireNonNull(instrumentId, "instrumentId");
        provider = requireText(provider, "provider");
        feed = requireText(feed, "feed");
        eventType = Objects.requireNonNull(eventType, "eventType");
        providerEventId = requireText(providerEventId, "providerEventId");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
        if (receivedAt.isBefore(occurredAt)) {
            throw new IllegalArgumentException("receivedAt must not precede occurredAt");
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        if (revision == 0 && correctionOfEventId != null) {
            throw new IllegalArgumentException("an original event cannot correct another event");
        }
        if (revision > 0) {
            correctionOfEventId = requireText(correctionOfEventId, "correctionOfEventId");
        }
        values = immutableSortedValues(values);
    }

    private static Map<String, BigDecimal> immutableSortedValues(Map<String, BigDecimal> source) {
        Objects.requireNonNull(source, "values");
        var sorted = new TreeMap<String, BigDecimal>();
        source.forEach((key, value) -> sorted.put(requireText(key, "value key"), Objects.requireNonNull(value, key)));
        if (sorted.isEmpty()) {
            throw new IllegalArgumentException("values must not be empty");
        }
        return Map.copyOf(sorted);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
