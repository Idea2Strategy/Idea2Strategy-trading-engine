package com.idea2strategy.trading.market.candle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.idea2strategy.trading.market.session.OfficialMarketSession;
import com.idea2strategy.trading.messaging.market.MarketCandle;
import com.idea2strategy.trading.messaging.market.MarketTimeframe;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class AlpacaThirtyMinuteBarsJsonParser {
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true)
            .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);

    public Map<String, List<MarketCandle>> parse(
            String body,
            Map<String, UUID> instruments,
            OfficialMarketSession session,
            Instant throughBoundary) {
        Objects.requireNonNull(instruments, "instruments");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(throughBoundary, "throughBoundary");
        try {
            JsonNode barsBySymbol = mapper.readTree(body).path("bars");
            if (!barsBySymbol.isObject()) {
                throw new IllegalArgumentException("Alpaca bars response is missing bars");
            }
            Map<String, List<MarketCandle>> result = new LinkedHashMap<>();
            for (Map.Entry<String, UUID> instrument : instruments.entrySet()) {
                JsonNode bars = barsBySymbol.path(instrument.getKey());
                List<MarketCandle> parsed = new ArrayList<>();
                if (bars.isArray()) {
                    for (JsonNode bar : bars) {
                        Instant opensAt = Instant.parse(requiredText(bar, "t"));
                        Instant closesAt = opensAt.plus(Duration.ofMinutes(30));
                        if (opensAt.isBefore(session.opensAt())
                                || closesAt.isAfter(session.closesAt())
                                || closesAt.isAfter(throughBoundary)) {
                            continue;
                        }
                        parsed.add(new MarketCandle(
                                instrument.getValue(),
                                MarketTimeframe.THIRTY_MINUTES,
                                opensAt,
                                closesAt,
                                decimal(bar, "o"),
                                decimal(bar, "h"),
                                decimal(bar, "l"),
                                decimal(bar, "c"),
                                decimal(bar, "v"),
                                false));
                    }
                }
                result.put(instrument.getKey(), List.copyOf(parsed));
            }
            return Map.copyOf(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Alpaca bars response is invalid JSON", exception);
        }
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("Alpaca bar field " + field + " must be text");
        }
        return value.textValue();
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isNumber()) {
            throw new IllegalArgumentException("Alpaca bar field " + field + " must be numeric");
        }
        return value.decimalValue();
    }
}
