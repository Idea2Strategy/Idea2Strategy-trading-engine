package com.idea2strategy.trading.market.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.trading.messaging.market.MarketEventEnvelope;
import com.idea2strategy.trading.messaging.market.MarketEventType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The field layout one market event occupies in the Redis stream, and how to read it back.
 *
 * <p>The layout is written by {@link RedisMarketEventPublisher}'s Lua script and read by the trading
 * worker's stream consumer. Both live behind this one decoder rather than each parsing the map, because
 * two implementations of the same layout drift silently: a consumer that read {@code values} with a
 * different Jackson type would quietly change a price's scale, and the price is what the evaluation
 * compares against a threshold.
 *
 * <p>{@code correctionOfEventId} is written as an empty string when absent — a Redis hash field cannot
 * be null — so an empty string reads back as absent here, which is the difference between an original
 * event and a malformed correction.
 */
public final class MarketEventStreamEntry {

    private static final TypeReference<Map<String, BigDecimal>> VALUES =
            new TypeReference<>() {};
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private MarketEventStreamEntry() {}

    /** Reads one stream entry's fields back into the envelope the gateway published. */
    public static MarketEventEnvelope decode(Map<String, String> fields) {
        Objects.requireNonNull(fields, "fields");
        try {
            return new MarketEventEnvelope(
                    required(fields, "eventId"),
                    Integer.parseInt(required(fields, "schemaVersion")),
                    UUID.fromString(required(fields, "instrumentId")),
                    required(fields, "provider"),
                    required(fields, "feed"),
                    MarketEventType.valueOf(required(fields, "eventType")),
                    required(fields, "providerEventId"),
                    Instant.parse(required(fields, "occurredAt")),
                    Instant.parse(required(fields, "receivedAt")),
                    Long.parseLong(required(fields, "sequence")),
                    Integer.parseInt(required(fields, "revision")),
                    emptyToNull(fields.get("correctionOfEventId")),
                    OBJECT_MAPPER.readValue(required(fields, "values"), VALUES));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("market event values are not valid JSON", exception);
        }
    }

    private static String required(Map<String, String> fields, String name) {
        String value = fields.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("market event entry is missing " + name);
        }
        return value;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
