package com.idea2strategy.trading.strategy.runtime.incremental;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class OrderedIncrementalFeatureRuntime {
    private final UUID botId;
    private final List<CalculatorBinding> calculators;
    private long lastSequence;
    private Map<FeatureKey, IncrementalFeatureState> featureStates;

    public OrderedIncrementalFeatureRuntime(
            UUID botId,
            long lastSequence,
            List<IncrementalFeatureCalculator> calculators,
            Map<FeatureKey, IncrementalFeatureState> initialStates) {
        this.botId = Objects.requireNonNull(botId, "botId");
        if (lastSequence < -1) {
            throw new IllegalArgumentException("lastSequence must be -1 or greater");
        }
        this.lastSequence = lastSequence;
        Objects.requireNonNull(calculators, "calculators");
        if (calculators.isEmpty()) {
            throw new IllegalArgumentException("calculators must not be empty");
        }

        Set<FeatureKey> calculatorKeys = new HashSet<>();
        List<CalculatorBinding> bindings = new ArrayList<>();
        for (IncrementalFeatureCalculator calculator : calculators) {
            Objects.requireNonNull(calculator, "calculator");
            FeatureKey key = Objects.requireNonNull(calculator.key(), "calculator key");
            if (!calculatorKeys.add(key)) {
                throw new IllegalArgumentException("calculator keys must be unique: " + key);
            }
            bindings.add(new CalculatorBinding(key, calculator));
        }
        this.calculators = List.copyOf(bindings);
        this.featureStates = Map.copyOf(Objects.requireNonNull(initialStates, "initialStates"));
        if (!this.featureStates.keySet().equals(calculatorKeys)) {
            throw new IllegalArgumentException("initialStates must exactly match calculator keys");
        }
    }

    public synchronized IncrementalFeatureSnapshot process(RuntimeTrigger trigger) {
        Objects.requireNonNull(trigger, "trigger");
        validateTrigger(trigger);

        Map<FeatureKey, IncrementalFeatureState> nextStates = new HashMap<>();
        for (CalculatorBinding binding : calculators) {
            FeatureKey key = binding.key();
            try {
                IncrementalFeatureState next = Objects.requireNonNull(
                        binding.calculator().calculate(featureStates.get(key), trigger),
                        "calculator result");
                nextStates.put(key, next);
            } catch (RuntimeException exception) {
                throw new IncrementalRuntimeException(
                        IncrementalRuntimeFailure.FEATURE_CALCULATION_FAILED,
                        key.featureId() + "@" + key.version(),
                        exception);
            }
        }

        featureStates = Map.copyOf(nextStates);
        lastSequence = trigger.sequence();
        return new IncrementalFeatureSnapshot(
                botId,
                trigger.sequence(),
                trigger.eventId(),
                trigger.type(),
                trigger.occurredAt(),
                featureStates);
    }

    public synchronized IncrementalRuntimeState state() {
        return new IncrementalRuntimeState(botId, lastSequence, featureStates);
    }

    private void validateTrigger(RuntimeTrigger trigger) {
        if (!botId.equals(trigger.botId())) {
            throw new IncrementalRuntimeException(
                    IncrementalRuntimeFailure.BOT_ID_MISMATCH,
                    trigger.botId() + " != " + botId);
        }
        if (trigger.sequence() <= lastSequence) {
            throw new IncrementalRuntimeException(
                    IncrementalRuntimeFailure.TRIGGER_SEQUENCE_DUPLICATE_OR_STALE,
                    trigger.sequence() + " <= " + lastSequence);
        }
        long expected = lastSequence + 1;
        if (trigger.sequence() != expected) {
            throw new IncrementalRuntimeException(
                    IncrementalRuntimeFailure.TRIGGER_SEQUENCE_GAP,
                    trigger.sequence() + " != " + expected);
        }
    }

    private record CalculatorBinding(FeatureKey key, IncrementalFeatureCalculator calculator) {
    }
}
