package com.idea2strategy.trading.market.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class AlpacaCalendarJsonParser {
    private final ObjectMapper objectMapper;

    public AlpacaCalendarJsonParser() {
        this(new ObjectMapper());
    }

    AlpacaCalendarJsonParser(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public OfficialMarketCalendarSnapshot parse(
            String responseBody,
            LocalDate coveredFrom,
            LocalDate coveredThrough,
            Instant fetchedAt) {
        try {
            JsonNode root = objectMapper.readTree(requireText(responseBody, "responseBody"));
            if (!root.isArray()) {
                throw new IllegalArgumentException("Alpaca calendar response must be an array");
            }
            List<OfficialMarketSession> sessions = new ArrayList<>();
            for (JsonNode entry : root) {
                LocalDate date = LocalDate.parse(required(entry, "date"));
                sessions.add(new OfficialMarketSession(
                        date,
                        parseMoment(date, required(entry, "open")),
                        parseMoment(date, required(entry, "close"))));
            }
            return new OfficialMarketCalendarSnapshot(
                    "alpaca",
                    "US_EQUITIES",
                    coveredFrom,
                    coveredThrough,
                    fetchedAt,
                    sessions);
        } catch (JsonProcessingException | DateTimeException exception) {
            throw new IllegalArgumentException("Alpaca calendar response is invalid", exception);
        }
    }

    private static Instant parseMoment(LocalDate date, String value) {
        if (value.indexOf('T') >= 0) {
            return OffsetDateTime.parse(value).toInstant();
        }
        return date.atTime(LocalTime.parse(value))
                .atZone(OfficialMarketSessionEvaluator.NEW_YORK)
                .toInstant();
    }

    private static String required(JsonNode entry, String field) {
        JsonNode value = entry.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("Alpaca calendar entry is missing " + field);
        }
        return value.textValue();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
