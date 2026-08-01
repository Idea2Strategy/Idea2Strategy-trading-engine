package com.idea2strategy.trading.strategy.runtime.judgment;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class JudgmentValueValidation {
    private JudgmentValueValidation() {
    }

    static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    static Map<String, String> immutableValues(Map<String, String> values, String name) {
        Objects.requireNonNull(values, name + " must not be null");
        Map<String, String> validated = new LinkedHashMap<>();
        values.forEach((key, value) -> validated.put(
                requireText(key, name + " key"),
                Objects.requireNonNull(value, name + " value must not be null")));
        return Map.copyOf(validated);
    }
}
