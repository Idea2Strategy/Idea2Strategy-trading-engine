package com.idea2strategy.trading.market.alpaca;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.idea2strategy.trading.messaging.market.MarketEventType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AlpacaSipMessageParser {
    private static final DateTimeFormatter BAR_EVENT_ID_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true)
            .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);
    private final AlpacaDataFeed feed;

    public AlpacaSipMessageParser() {
        this(AlpacaDataFeed.SIP);
    }

    public AlpacaSipMessageParser(AlpacaDataFeed feed) {
        this.feed = Objects.requireNonNull(feed, "feed");
    }

    public List<AlpacaSipInboundMessage> parse(String frame, Instant receivedAt) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(receivedAt, "receivedAt");
        JsonNode root;
        try {
            root = mapper.readTree(frame);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("SIP frame is not valid JSON", exception);
        }
        if (!root.isArray()) {
            throw new IllegalArgumentException("SIP frame must be a JSON array of messages");
        }
        List<AlpacaSipInboundMessage> messages = new ArrayList<>();
        for (JsonNode node : root) {
            messages.add(message(node, receivedAt, feed));
        }
        return List.copyOf(messages);
    }

    private static AlpacaSipInboundMessage message(
            JsonNode node, Instant receivedAt, AlpacaDataFeed feed) {
        String type = text(node, "T");
        return switch (type) {
            case "success" -> switch (text(node, "msg")) {
                case "connected" -> new AlpacaSipInboundMessage.Connected();
                case "authenticated" -> new AlpacaSipInboundMessage.Authenticated();
                default -> new AlpacaSipInboundMessage.UnsupportedFrame("success:" + text(node, "msg"));
            };
            case "subscription" -> new AlpacaSipInboundMessage.SubscriptionConfirmed(symbols(node));
            case "error" -> new AlpacaSipInboundMessage.ProviderError(
                    node.path("code").asInt(), node.path("msg").asText(""));
            case "b" -> new AlpacaSipInboundMessage.MinuteBar(bar(node, receivedAt, feed));
            default -> new AlpacaSipInboundMessage.UnsupportedFrame(type);
        };
    }

    private static AlpacaMarketInput bar(JsonNode node, Instant receivedAt, AlpacaDataFeed feed) {
        Instant occurredAt = instant(node);
        return new AlpacaMarketInput(
                MarketEventType.BAR_1M,
                "bar-" + BAR_EVENT_ID_FORMAT.format(occurredAt),
                text(node, "S"),
                feed.eventValue(),
                occurredAt,
                receivedAt,
                occurredAt.getEpochSecond() / 60,
                0,
                Map.of(
                        "open", decimal(node, "o"),
                        "high", decimal(node, "h"),
                        "low", decimal(node, "l"),
                        "close", decimal(node, "c"),
                        "volume", decimal(node, "v")));
    }

    private static List<String> symbols(JsonNode node) {
        JsonNode bars = node.path("bars");
        if (!bars.isArray()) {
            throw new IllegalArgumentException("subscription frame is missing the bars symbol list");
        }
        List<String> symbols = new ArrayList<>();
        for (JsonNode symbol : bars) {
            symbols.add(symbol.asText());
        }
        return symbols;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("SIP message field " + field + " must be present text");
        }
        return value.asText();
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isNumber()) {
            throw new IllegalArgumentException("SIP bar field " + field + " must be a number");
        }
        return value.decimalValue();
    }

    private static Instant instant(JsonNode node) {
        try {
            return Instant.parse(text(node, "t"));
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("SIP bar field t must be an RFC-3339 instant", exception);
        }
    }
}
