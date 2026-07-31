package com.idea2strategy.trading.market.alpaca;

import com.idea2strategy.trading.messaging.market.MarketEventEnvelope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

public final class AlpacaMarketEventNormalizer {
    private static final int SCHEMA_VERSION = 1;
    private static final String PROVIDER = "ALPACA";

    private final Map<String, UUID> supportedInstruments;

    public AlpacaMarketEventNormalizer(Map<String, UUID> supportedInstruments) {
        Objects.requireNonNull(supportedInstruments, "supportedInstruments");
        var normalized = new TreeMap<String, UUID>();
        supportedInstruments.forEach((symbol, instrumentId) -> normalized.put(
                normalizeToken(symbol, "symbol"), Objects.requireNonNull(instrumentId, symbol)));
        this.supportedInstruments = Map.copyOf(normalized);
    }

    public MarketEventEnvelope normalize(AlpacaMarketInput input) {
        Objects.requireNonNull(input, "input");
        var symbol = normalizeToken(input.symbol(), "symbol");
        var instrumentId = supportedInstruments.get(symbol);
        if (instrumentId == null) {
            throw new UnsupportedInstrumentException(symbol);
        }
        var feed = normalizeToken(input.feed(), "feed");
        var eventId = eventId(input, instrumentId, feed, input.revision());
        var correctionOf = input.revision() == 0 ? null : eventId(input, instrumentId, feed, 0);

        return new MarketEventEnvelope(
                eventId,
                SCHEMA_VERSION,
                instrumentId,
                PROVIDER,
                feed,
                input.eventType(),
                input.providerEventId(),
                input.occurredAt(),
                input.receivedAt(),
                input.sequence(),
                input.revision(),
                correctionOf,
                input.values());
    }

    private static String eventId(AlpacaMarketInput input, UUID instrumentId, String feed, int revision) {
        var values = new TreeMap<>(input.values()).entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue().stripTrailingZeros().toPlainString())
                .collect(Collectors.joining("&"));
        var canonical = String.join(
                "\n",
                PROVIDER,
                feed,
                input.eventType().name(),
                instrumentId.toString(),
                input.providerEventId(),
                input.occurredAt().toString(),
                Long.toString(input.sequence()),
                Integer.toString(revision),
                values);
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return "evt_" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private static String normalizeToken(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
