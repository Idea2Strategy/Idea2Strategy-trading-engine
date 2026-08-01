package com.idea2strategy.trading.strategy.runtime.revalidation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class RevalidationValueValidation {
    private RevalidationValueValidation() {
    }

    static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    static Map<UUID, String> immutableVersions(Map<UUID, String> versions, String name) {
        Objects.requireNonNull(versions, name + " must not be null");
        Map<UUID, String> validated = new LinkedHashMap<>();
        versions.forEach((instrumentId, version) -> validated.put(
                Objects.requireNonNull(instrumentId, name + " instrumentId must not be null"),
                requireText(version, name + " version")));
        return Map.copyOf(validated);
    }
}
