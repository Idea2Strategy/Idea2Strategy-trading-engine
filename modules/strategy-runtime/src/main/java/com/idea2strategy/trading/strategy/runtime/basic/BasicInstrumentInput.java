package com.idea2strategy.trading.strategy.runtime.basic;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record BasicInstrumentInput(UUID instrumentId, Map<String, String> values) {
    public BasicInstrumentInput {
        instrumentId = Objects.requireNonNull(instrumentId, "instrumentId must not be null");
        values = Map.copyOf(Objects.requireNonNull(values, "values must not be null"));
    }
}
