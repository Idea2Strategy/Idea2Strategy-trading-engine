package com.idea2strategy.trading.strategy.runtime.feature;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idea2strategy.trading.strategy.runtime.incremental.IncrementalFeatureState;
import com.idea2strategy.trading.strategy.runtime.incremental.OrderedIncrementalFeatureRuntime;
import com.idea2strategy.trading.strategy.runtime.incremental.RuntimeTrigger;
import com.idea2strategy.trading.strategy.runtime.incremental.RuntimeTriggerType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The catalog driven from the live event stream, with its window in runtime state.
 *
 * <p>What matters here is not the arithmetic — {@code OfficialFeatureCatalogTest} pins that against
 * D's vectors — but that the value a bot computes is a pure function of its persisted state, so a
 * restart mid-stream cannot change it.
 */
class BoundedWindowFeatureCalculatorTest {

    private static final UUID BOT = UUID.fromString("c1000000-0000-4000-8000-000000000001");
    private static final Instant T0 = Instant.parse("2026-08-03T14:30:00Z");

    private final BoundedWindowFeatureCalculator calculator =
            new BoundedWindowFeatureCalculator(OfficialFeatureCatalog.RSI_14);

    @Test
    void publishesNoValueUntilTheWindowIsFull() {
        IncrementalFeatureState state = empty();
        for (int index = 0; index < 14; index++) {
            state = calculator.calculate(state, closeTrigger(index, 100 + index));
            assertTrue(calculator.valueOf(state).isEmpty(),
                    "a partial window must carry no value at all, not a zero");
        }

        state = calculator.calculate(state, closeTrigger(14, 114));
        assertAll(
                () -> assertEquals(15, calculator.windowOf(stateOf(114)).size()),
                () -> assertEquals("100.00000000",
                        calculator.valueOf(stateOf(114)).orElseThrow().toPlainString()));
    }

    /** The window slides: only the newest 15 closes are ever retained. */
    @Test
    void keepsOnlyTheNewestFifteenCloses() {
        IncrementalFeatureState state = empty();
        for (int index = 0; index < 30; index++) {
            state = calculator.calculate(state, closeTrigger(index, 100));
        }
        // Thirty flat closes, so the retained window is flat and the value is the pinned 50.
        assertAll(
                () -> assertEquals(15, calculator.windowOf(stateFromFlatRun()).size()),
                () -> assertEquals("50.00000000",
                        calculator.valueOf(stateFromFlatRun()).orElseThrow().toPlainString()));
    }

    /** A trigger with no close does not advance the window, so no stale value is shifted out. */
    @Test
    void ignoresATriggerThatCarriesNoClose() {
        IncrementalFeatureState state = calculator.calculate(empty(), closeTrigger(0, 100));
        IncrementalFeatureState unchanged = calculator.calculate(state, new RuntimeTrigger(
                BOT, 1, "timer-1", RuntimeTriggerType.TIMER, T0.plusSeconds(60), Map.of()));

        assertSame(state, unchanged);
    }

    /**
     * The property C19 turns on: a process that restores the persisted state and continues reaches
     * the same value as one that never stopped.
     */
    @Test
    void aRestoredStateContinuesToTheSameValue() {
        var uninterrupted = new OrderedIncrementalFeatureRuntime(
                BOT, -1, List.of(calculator), Map.of(calculator.key(), empty()));
        for (int index = 0; index < 20; index++) {
            uninterrupted.process(closeTrigger(index, 100 + (index % 5)));
        }

        var beforeRestart = new OrderedIncrementalFeatureRuntime(
                BOT, -1, List.of(calculator), Map.of(calculator.key(), empty()));
        for (int index = 0; index < 12; index++) {
            beforeRestart.process(closeTrigger(index, 100 + (index % 5)));
        }
        var persisted = beforeRestart.state();

        var afterRestart = new OrderedIncrementalFeatureRuntime(
                BOT, persisted.lastSequence(), List.of(calculator), persisted.featureStates());
        for (int index = 12; index < 20; index++) {
            afterRestart.process(closeTrigger(index, 100 + (index % 5)));
        }

        assertEquals(uninterrupted.state(), afterRestart.state());
        assertEquals(
                calculator.valueOf(uninterrupted.state().featureStates().get(calculator.key())),
                calculator.valueOf(afterRestart.state().featureStates().get(calculator.key())));
    }

    private IncrementalFeatureState empty() {
        return new IncrementalFeatureState(0, Map.of());
    }

    private RuntimeTrigger closeTrigger(int sequence, int close) {
        return new RuntimeTrigger(
                BOT, sequence, "market-" + sequence, RuntimeTriggerType.MARKET,
                T0.plusSeconds(60L * sequence),
                Map.of(BoundedWindowFeatureCalculator.CLOSE_TRIGGER_VALUE, BigDecimal.valueOf(close)));
    }

    /** Replays fifteen ascending closes ending at {@code last} and returns the resulting state. */
    private IncrementalFeatureState stateOf(int last) {
        IncrementalFeatureState state = empty();
        for (int close = last - 14; close <= last; close++) {
            state = calculator.calculate(state, closeTrigger(close, close));
        }
        return state;
    }

    private IncrementalFeatureState stateFromFlatRun() {
        IncrementalFeatureState state = empty();
        for (int index = 0; index < 30; index++) {
            state = calculator.calculate(state, closeTrigger(index, 100));
        }
        return state;
    }
}
