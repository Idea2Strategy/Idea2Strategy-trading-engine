package com.idea2strategy.trading.strategy.runtime.incremental;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderedIncrementalFeatureRuntimeTest {
    private static final UUID BOT_ID = UUID.fromString("b274523a-e318-4b7b-81bd-d3458738a690");
    private static final FeatureKey FEATURE = new FeatureKey("test-counter", "1.0.0");

    @Test
    void rejectsADuplicateSequenceWithoutChangingFeatureState() {
        OrderedIncrementalFeatureRuntime runtime = runtime(6, List.of(counter(FEATURE)), Map.of(
                FEATURE, new IncrementalFeatureState(0, Map.of("count", BigDecimal.ZERO))));
        runtime.process(trigger(7, RuntimeTriggerType.MARKET));

        IncrementalRuntimeException exception = assertThrows(
                IncrementalRuntimeException.class,
                () -> runtime.process(trigger(7, RuntimeTriggerType.FILL)));

        assertEquals(IncrementalRuntimeFailure.TRIGGER_SEQUENCE_DUPLICATE_OR_STALE, exception.failure());
        assertEquals(7, runtime.state().lastSequence());
        assertEquals(new BigDecimal("1"), runtime.state().featureStates().get(FEATURE).values().get("count"));
    }

    @Test
    void processesEveryTriggerTypeInOneSequenceAndReturnsImmutableSnapshots() {
        List<RuntimeTriggerType> received = new ArrayList<>();
        IncrementalFeatureCalculator calculator = recordingCounter(FEATURE, received);
        OrderedIncrementalFeatureRuntime runtime = runtime(-1, List.of(calculator), Map.of(
                FEATURE, new IncrementalFeatureState(0, Map.of("count", BigDecimal.ZERO))));

        IncrementalFeatureSnapshot snapshot = null;
        RuntimeTriggerType[] types = RuntimeTriggerType.values();
        for (int sequence = 0; sequence < types.length; sequence++) {
            snapshot = runtime.process(trigger(sequence, types[sequence]));
        }

        assertEquals(List.of(
                RuntimeTriggerType.MARKET,
                RuntimeTriggerType.TIMER,
                RuntimeTriggerType.ORDER,
                RuntimeTriggerType.FILL), received);
        assertEquals(3, snapshot.triggerSequence());
        assertEquals(RuntimeTriggerType.FILL, snapshot.triggerType());
        assertEquals(new BigDecimal("4"), snapshot.featureStates().get(FEATURE).values().get("count"));
        IncrementalFeatureSnapshot finalSnapshot = snapshot;
        assertThrows(UnsupportedOperationException.class,
                () -> finalSnapshot.featureStates().put(FEATURE, new IncrementalFeatureState(0, Map.of())));
        assertThrows(UnsupportedOperationException.class,
                () -> finalSnapshot.featureStates().get(FEATURE).values().put("count", BigDecimal.TEN));
    }

    @Test
    void rejectsSequenceGapsAndOtherBotsWithoutChangingState() {
        IncrementalFeatureState initial = new IncrementalFeatureState(2, Map.of("count", new BigDecimal("2")));
        OrderedIncrementalFeatureRuntime runtime = runtime(5, List.of(counter(FEATURE)), Map.of(FEATURE, initial));

        assertFailure(runtime, trigger(7, RuntimeTriggerType.MARKET),
                IncrementalRuntimeFailure.TRIGGER_SEQUENCE_GAP);
        RuntimeTrigger otherBot = new RuntimeTrigger(
                UUID.fromString("23354f1e-d4c2-428f-991a-f91bc2114107"),
                6, "other-bot-event", RuntimeTriggerType.TIMER,
                Instant.parse("2026-08-01T14:30:06Z"), Map.of());
        assertFailure(runtime, otherBot, IncrementalRuntimeFailure.BOT_ID_MISMATCH);

        assertEquals(new IncrementalRuntimeState(BOT_ID, 5, Map.of(FEATURE, initial)), runtime.state());
    }

    @Test
    void keepsAllFeaturesAtThePreviousSequenceWhenOneCalculatorFails() {
        FeatureKey failingKey = new FeatureKey("failing", "3.1.0");
        IncrementalFeatureState counterInitial = new IncrementalFeatureState(4, Map.of("count", new BigDecimal("4")));
        IncrementalFeatureState failingInitial = new IncrementalFeatureState(9, Map.of("value", new BigDecimal("9")));
        IncrementalFeatureCalculator failing = new IncrementalFeatureCalculator() {
            @Override
            public FeatureKey key() {
                return failingKey;
            }

            @Override
            public IncrementalFeatureState calculate(IncrementalFeatureState current, RuntimeTrigger trigger) {
                throw new IllegalStateException("catalog calculator rejected input");
            }
        };
        OrderedIncrementalFeatureRuntime runtime = runtime(
                11,
                List.of(counter(FEATURE), failing),
                Map.of(FEATURE, counterInitial, failingKey, failingInitial));

        IncrementalRuntimeException exception = assertThrows(
                IncrementalRuntimeException.class,
                () -> runtime.process(trigger(12, RuntimeTriggerType.ORDER)));

        assertEquals(IncrementalRuntimeFailure.FEATURE_CALCULATION_FAILED, exception.failure());
        assertTrue(exception.getMessage().contains("failing@3.1.0"));
        assertEquals(new IncrementalRuntimeState(
                BOT_ID, 11, Map.of(FEATURE, counterInitial, failingKey, failingInitial)), runtime.state());
    }

    @Test
    void requiresExactVersionedInitialStatesAndCopiesMutableInputs() {
        FeatureKey otherVersion = new FeatureKey(FEATURE.featureId(), "2.0.0");
        assertThrows(IllegalArgumentException.class,
                () -> runtime(-1, List.of(counter(FEATURE)), Map.of(
                        otherVersion, new IncrementalFeatureState(0, Map.of()))));
        assertThrows(IllegalArgumentException.class,
                () -> runtime(-1, List.of(counter(FEATURE), counter(FEATURE)), Map.of(
                        FEATURE, new IncrementalFeatureState(0, Map.of()))));

        Map<String, BigDecimal> mutableValues = new LinkedHashMap<>(Map.of("count", BigDecimal.ZERO));
        IncrementalFeatureState copiedState = new IncrementalFeatureState(0, mutableValues);
        Map<FeatureKey, IncrementalFeatureState> mutableStates = new LinkedHashMap<>(Map.of(FEATURE, copiedState));
        OrderedIncrementalFeatureRuntime runtime = runtime(-1, List.of(counter(FEATURE)), mutableStates);
        mutableValues.put("count", BigDecimal.TEN);
        mutableStates.clear();

        assertEquals(BigDecimal.ZERO, runtime.state().featureStates().get(FEATURE).values().get("count"));
    }

    private static OrderedIncrementalFeatureRuntime runtime(
            long lastSequence,
            List<IncrementalFeatureCalculator> calculators,
            Map<FeatureKey, IncrementalFeatureState> initialStates) {
        return new OrderedIncrementalFeatureRuntime(BOT_ID, lastSequence, calculators, initialStates);
    }

    private static IncrementalFeatureCalculator counter(FeatureKey key) {
        return recordingCounter(key, new ArrayList<>());
    }

    private static IncrementalFeatureCalculator recordingCounter(
            FeatureKey key, List<RuntimeTriggerType> received) {
        return new IncrementalFeatureCalculator() {
            @Override
            public FeatureKey key() {
                return key;
            }

            @Override
            public IncrementalFeatureState calculate(IncrementalFeatureState current, RuntimeTrigger trigger) {
                received.add(trigger.type());
                BigDecimal count = current.values().get("count").add(BigDecimal.ONE);
                return new IncrementalFeatureState(current.updateCount() + 1, Map.of("count", count));
            }
        };
    }

    private static RuntimeTrigger trigger(long sequence, RuntimeTriggerType type) {
        return new RuntimeTrigger(
                BOT_ID, sequence, "event-" + sequence + "-" + type,
                type, Instant.parse("2026-08-01T14:30:00Z").plusSeconds(sequence), Map.of());
    }

    private static void assertFailure(
            OrderedIncrementalFeatureRuntime runtime,
            RuntimeTrigger trigger,
            IncrementalRuntimeFailure expected) {
        IncrementalRuntimeException exception = assertThrows(
                IncrementalRuntimeException.class, () -> runtime.process(trigger));
        assertEquals(expected, exception.failure());
    }
}
