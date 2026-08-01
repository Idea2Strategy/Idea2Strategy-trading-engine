package com.idea2strategy.trading.strategy.runtime.basic;

import java.util.Map;
import java.util.Objects;

public record BasicStepTrace(String stepId, boolean passed, String reasonCode, Map<String, String> evidence) {
    public BasicStepTrace {
        stepId = BasicValueValidation.requireText(stepId, "stepId");
        reasonCode = BasicValueValidation.requireText(reasonCode, "reasonCode");
        evidence = Map.copyOf(Objects.requireNonNull(evidence, "evidence must not be null"));
    }
}
