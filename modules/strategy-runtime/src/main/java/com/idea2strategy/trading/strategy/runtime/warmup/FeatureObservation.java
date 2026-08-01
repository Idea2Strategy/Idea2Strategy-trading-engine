package com.idea2strategy.trading.strategy.runtime.warmup;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record FeatureObservation(String instrument, Instant observedAt, BigDecimal value) {
    public FeatureObservation {
        instrument = WarmupValueValidation.requireText(instrument, "instrument");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        value = Objects.requireNonNull(value, "value");
    }
}
