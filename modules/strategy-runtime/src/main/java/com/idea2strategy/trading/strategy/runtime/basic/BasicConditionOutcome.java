package com.idea2strategy.trading.strategy.runtime.basic;

import java.util.Map;
import java.util.Objects;

public record BasicConditionOutcome(boolean passed, String reasonCode, Map<String, String> evidence) {
    public BasicConditionOutcome {
        reasonCode = BasicValueValidation.requireText(reasonCode, "reasonCode");
        evidence = Map.copyOf(Objects.requireNonNull(evidence, "evidence must not be null"));
    }

    public static BasicConditionOutcome passed(String reasonCode) {
        return new BasicConditionOutcome(true, reasonCode, Map.of());
    }

    public static BasicConditionOutcome failed(String reasonCode) {
        return new BasicConditionOutcome(false, reasonCode, Map.of());
    }
}
