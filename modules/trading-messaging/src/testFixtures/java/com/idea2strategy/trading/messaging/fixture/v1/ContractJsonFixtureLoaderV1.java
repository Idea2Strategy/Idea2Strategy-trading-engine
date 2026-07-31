package com.idea2strategy.trading.messaging.fixture.v1;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;

public final class ContractJsonFixtureLoaderV1 {
    private static final String RESOURCE_DIRECTORY = "contracts/trading/v1/";
    private static final List<String> ENVELOPE_RESOURCES = List.of(
        "intent-batch.json",
        "order-accepted.json",
        "order-partial-fill.json",
        "order-filled.json",
        "order-cancelled.json",
        "order-rejected.json",
        "settlement-completed.json",
        "ledger-transaction.json"
    );

    private ContractJsonFixtureLoaderV1() {
    }

    public static ObjectMapper mapper() {
        return new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public static <T> T read(String resource, TypeReference<T> type) {
        return readResource(RESOURCE_DIRECTORY + resource, type);
    }

    public static <T> T readResource(String resource, TypeReference<T> type) {
        try (InputStream input = resourceAt(resource)) {
            return mapper().readValue(input, type);
        } catch (IOException exception) {
            throw new UncheckedIOException("unable to read canonical JSON fixture " + resource, exception);
        }
    }

    public static boolean roundTrips(String resource, TypeReference<?> type) {
        Object value = read(resource, type);
        try (InputStream input = resourceAt(RESOURCE_DIRECTORY + resource)) {
            JsonNode source = mapper().readTree(input);
            JsonNode serialized = mapper().readTree(mapper().writeValueAsBytes(value));
            return source.equals(serialized);
        } catch (IOException exception) {
            throw new UncheckedIOException("unable to round-trip canonical JSON fixture " + resource, exception);
        }
    }

    public static List<UUID> canonicalEnvelopeEventIds() {
        return ENVELOPE_RESOURCES.stream()
            .map(resource -> read(resource, new TypeReference<JsonNode>() {}))
            .map(node -> UUID.fromString(node.required("eventId").asText()))
            .toList();
    }

    private static InputStream resourceAt(String name) {
        InputStream input = ContractJsonFixtureLoaderV1.class.getClassLoader().getResourceAsStream(name);
        if (input == null) {
            throw new IllegalArgumentException("canonical JSON fixture not found: " + name);
        }
        return input;
    }
}
