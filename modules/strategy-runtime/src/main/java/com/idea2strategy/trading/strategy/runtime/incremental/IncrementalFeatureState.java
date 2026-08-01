package com.idea2strategy.trading.strategy.runtime.incremental;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public record IncrementalFeatureState(long updateCount, Map<String, BigDecimal> values) {
    public IncrementalFeatureState {
        if (updateCount < 0) {
            throw new IllegalArgumentException("updateCount must not be negative");
        }
        Objects.requireNonNull(values, "values");
        TreeMap<String, BigDecimal> copy = new TreeMap<>();
        values.forEach((key, value) -> copy.put(
                IncrementalValueValidation.requireText(key, "state key"),
                Objects.requireNonNull(value, key)));
        values = Map.copyOf(copy);
    }
}
