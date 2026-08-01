package com.idea2strategy.trading.strategy.runtime.judgment;

import java.util.Map;

public record ProjectedRuntimeState(long revision, Map<String, String> values) {
    public ProjectedRuntimeState {
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        values = JudgmentValueValidation.immutableValues(values, "values");
    }
}
