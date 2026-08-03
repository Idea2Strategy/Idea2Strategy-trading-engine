package com.idea2strategy.trading.strategy.runtime.basic;

import java.util.Map;
import java.util.Objects;

/**
 * Typed signal a condition step throws when the data it needs never arrived, as opposed to the
 * condition itself failing to evaluate. The executor classifies it as
 * {@link BasicDecisionStatus#INPUT_MISSING}, never {@link BasicDecisionStatus#CONDITION_ERROR}:
 * a data gap and a genuine evaluation fault must stay distinguishable in anything that counts
 * failures by status (D92, basic-executor-conformance.v1 knownDivergences[mid-step-input-missing]).
 */
public final class BasicInputMissingException extends RuntimeException {

    private final String inputReason;
    private final Map<String, String> evidence;

    public BasicInputMissingException(String inputReason, Map<String, String> evidence) {
        super(inputReason);
        this.inputReason = BasicValueValidation.requireText(inputReason, "inputReason");
        this.evidence = Map.copyOf(Objects.requireNonNull(evidence, "evidence must not be null"));
    }

    public String inputReason() {
        return inputReason;
    }

    public Map<String, String> evidence() {
        return evidence;
    }
}
