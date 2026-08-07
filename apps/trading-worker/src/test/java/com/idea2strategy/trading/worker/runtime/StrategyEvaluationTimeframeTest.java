package com.idea2strategy.trading.worker.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class StrategyEvaluationTimeframeTest {
    @Test
    void resolvesSupportedCadencesAndMigratesLockedOneMinutePlansToThirtyMinutes() {
        assertEquals(StrategyEvaluationTimeframe.FOUR_HOURS,
                StrategyEvaluationTimeframe.fromPlan("{\"resolution\":\"4h\"}"));
        assertEquals(StrategyEvaluationTimeframe.THIRTY_MINUTES,
                StrategyEvaluationTimeframe.fromPlan("{\"resolution\":\"PT1M\"}"));
    }

    @Test
    void rejectsMixedCadencesBecauseOneFeatureStateCannotRepresentBoth() {
        assertThrows(IllegalArgumentException.class, () -> StrategyEvaluationTimeframe.fromPlan(
                "{\"steps\":[{\"resolution\":\"30m\"},{\"resolution\":\"1h\"}]}"));
    }
}
