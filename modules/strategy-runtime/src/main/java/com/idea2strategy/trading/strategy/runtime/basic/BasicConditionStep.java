package com.idea2strategy.trading.strategy.runtime.basic;

import java.util.Objects;
import java.util.function.Function;

public record BasicConditionStep(
        String stepId,
        Function<BasicInstrumentInput, BasicConditionOutcome> evaluator) {

    public BasicConditionStep {
        stepId = BasicValueValidation.requireText(stepId, "stepId");
        evaluator = Objects.requireNonNull(evaluator, "evaluator must not be null");
    }

    BasicConditionOutcome evaluate(BasicInstrumentInput input) {
        return evaluator.apply(input);
    }
}
