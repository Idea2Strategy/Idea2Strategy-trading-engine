package com.idea2strategy.trading.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

final class FileInstrumentMapping {
    private static final TypeReference<Map<String, String>> MAPPING_TYPE = new TypeReference<>() {};

    private FileInstrumentMapping() {}

    static Map<String, UUID> load(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("instrument mapping file is missing: " + path);
        }
        Map<String, String> raw;
        try {
            raw = new ObjectMapper().readValue(Files.readAllBytes(path), MAPPING_TYPE);
        } catch (IOException exception) {
            throw new IllegalStateException("instrument mapping cannot be read: " + path, exception);
        }
        if (raw.isEmpty()) {
            throw new IllegalStateException("instrument mapping must not be empty: " + path);
        }
        Map<String, UUID> mapping = new TreeMap<>();
        raw.forEach((symbol, instrumentId) -> {
            try {
                mapping.put(symbol, UUID.fromString(instrumentId));
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException(
                        "instrument mapping for " + symbol + " is not a UUID: " + instrumentId, exception);
            }
        });
        return Map.copyOf(mapping);
    }
}
