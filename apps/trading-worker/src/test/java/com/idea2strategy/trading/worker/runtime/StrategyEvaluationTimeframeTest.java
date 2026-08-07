package com.idea2strategy.trading.worker.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import java.util.Set;

class StrategyEvaluationTimeframeTest {
    @Test
    void resolvesEverySupportedCadenceIncludingMixedPlans() {
        assertEquals(Set.of(StrategyEvaluationTimeframe.FOUR_HOURS),
                StrategyEvaluationTimeframe.fromPlan("{\"resolution\":\"4h\"}"));
        assertEquals(
                Set.of(StrategyEvaluationTimeframe.THIRTY_MINUTES, StrategyEvaluationTimeframe.ONE_HOUR),
                StrategyEvaluationTimeframe.fromPlan(
                        "{\"steps\":[{\"resolution\":\"30m\"},{\"resolution\":\"1h\"}]}"));
    }

    @Test
    void rejectsDisplayOnlyOneMinuteCadence() {
        assertThrows(IllegalArgumentException.class,
                () -> StrategyEvaluationTimeframe.fromPlan("{\"resolution\":\"PT1M\"}"));
    }
}
