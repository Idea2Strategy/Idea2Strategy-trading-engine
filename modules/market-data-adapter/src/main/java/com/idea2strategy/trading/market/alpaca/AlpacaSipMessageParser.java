package com.idea2strategy.trading.market.alpaca;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class AlpacaSipMessageParser {
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
            case "subscription" -> new AlpacaSipInboundMessage.SubscriptionConfirmed(symbols(node, "trades"));
            case "error" -> new AlpacaSipInboundMessage.ProviderError(
                    node.path("code").asInt(), node.path("msg").asText(""));
            case "b" -> new AlpacaSipInboundMessage.UnsupportedFrame("b");
            case "t" -> trade(node, receivedAt);
            default -> new AlpacaSipInboundMessage.UnsupportedFrame(type);
        };
    }

    private static AlpacaSipInboundMessage.TradeTick trade(JsonNode node, Instant receivedAt) {
        return new AlpacaSipInboundMessage.TradeTick(
                text(node, "S"),
                integer(node, "i"),
                text(node, "x"),
                decimal(node, "p"),
                decimal(node, "s"),
                instant(node, "t"),
                receivedAt,
                strings(node, "c"),
                text(node, "z"));
    }

    private static List<String> symbols(JsonNode node, String field) {
        JsonNode values = node.path(field);
        if (!values.isArray()) {
            throw new IllegalArgumentException("subscription frame is missing the " + field + " symbol list");
        }
        List<String> symbols = new ArrayList<>();
        for (JsonNode symbol : values) {
            symbols.add(symbol.asText());
        }
        return symbols;
    }

    private static List<String> strings(JsonNode node, String field) {
        JsonNode values = node.path(field);
        if (!values.isArray()) {
            throw new IllegalArgumentException("SIP trade field " + field + " must be an array");
        }
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asText()));
        return result;
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isNumber()) {
            throw new IllegalArgumentException("SIP trade field " + field + " must be a number");
        }
        return value.decimalValue();
    }

    private static long integer(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.canConvertToLong()) {
            throw new IllegalArgumentException("SIP trade field " + field + " must be an integer");
        }
        return value.longValue();
    }

    private static Instant instant(JsonNode node, String field) {
        try {
            return Instant.parse(text(node, field));
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("SIP trade field " + field + " must be an RFC-3339 instant", exception);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("SIP message field " + field + " must be present text");
        }
        return value.asText();
    }

}
