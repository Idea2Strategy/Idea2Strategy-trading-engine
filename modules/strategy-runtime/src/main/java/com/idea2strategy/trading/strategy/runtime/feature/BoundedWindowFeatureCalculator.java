package com.idea2strategy.trading.strategy.runtime.feature;

import com.idea2strategy.trading.strategy.runtime.incremental.FeatureKey;
import com.idea2strategy.trading.strategy.runtime.incremental.IncrementalFeatureCalculator;
import com.idea2strategy.trading.strategy.runtime.incremental.IncrementalFeatureState;
import com.idea2strategy.trading.strategy.runtime.incremental.RuntimeTrigger;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Drives an {@link OfficialFeature} from the live event stream, carrying its window in runtime state.
 *
 * <p>A bounded-window definition is a pure function of its last {@code requiredBars} closes, so the
 * whole of the runtime state this needs is that window. Keeping it in
 * {@link IncrementalFeatureState} rather than in a field is what lets the ordered runtime restore a
 * bot after a restart and continue with the same value — the property C19 proves and the reason the
 * catalog rejects unbounded recursions.
 *
 * <p>The window is stored as {@code close.0} … {@code close.N}, oldest first, and the computed value
 * under {@link #VALUE_KEY}. Until the window is full there is no value at all: the state carries the
 * partial window and no {@code value} key, which is how a reader tells "warm-up incomplete" from a
 * genuine zero. Callers must treat a missing value as {@code FEATURE_WARMUP_INCOMPLETE} and never as
 * 0, 50 or 100.
 */
public final class BoundedWindowFeatureCalculator implements IncrementalFeatureCalculator {

    /** The state key the computed feature value is published under. */
    public static final String VALUE_KEY = "value";

    /** The state key prefix of the retained window, oldest first. */
    public static final String CLOSE_KEY_PREFIX = "close.";

    /** The trigger value this calculator reads a close from. */
    public static final String CLOSE_TRIGGER_VALUE = "close";

    private final OfficialFeature feature;
    private final FeatureKey key;

    public BoundedWindowFeatureCalculator(OfficialFeature feature) {
        this.feature = Objects.requireNonNull(feature, "feature");
        this.key = new FeatureKey(feature.featureId(), feature.semanticVersion());
    }

    @Override
    public FeatureKey key() {
        return key;
    }

    @Override
    public IncrementalFeatureState calculate(IncrementalFeatureState current, RuntimeTrigger trigger) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(trigger, "trigger");
        BigDecimal close = trigger.values().get(CLOSE_TRIGGER_VALUE);
        if (close == null) {
            // Not every trigger carries a close — a timer or an order event does not. The window is
            // only advanced by an observation that actually moves it, so unrelated triggers leave the
            // feature exactly as it was rather than shifting a stale value out of the window.
            return current;
        }

        List<BigDecimal> window = new ArrayList<>(windowOf(current));
        window.add(close);
        while (window.size() > feature.requiredBars()) {
            window.removeFirst();
        }

        Map<String, BigDecimal> values = new LinkedHashMap<>();
        for (int index = 0; index < window.size(); index++) {
            values.put(CLOSE_KEY_PREFIX + index, window.get(index));
        }
        if (window.size() == feature.requiredBars()) {
            values.put(VALUE_KEY, feature.compute(window));
        }
        return new IncrementalFeatureState(current.updateCount() + 1, values);
    }

    /** The retained window of a state, oldest first. */
    public List<BigDecimal> windowOf(IncrementalFeatureState state) {
        Objects.requireNonNull(state, "state");
        List<BigDecimal> window = new ArrayList<>();
        for (int index = 0; index < feature.requiredBars(); index++) {
            BigDecimal close = state.values().get(CLOSE_KEY_PREFIX + index);
            if (close == null) {
                break;
            }
            window.add(close);
        }
        return List.copyOf(window);
    }

    /** The feature's value, or empty while the window is still filling. */
    public Optional<BigDecimal> valueOf(IncrementalFeatureState state) {
        Objects.requireNonNull(state, "state");
        return Optional.ofNullable(state.values().get(VALUE_KEY));
    }

    public OfficialFeature feature() {
        return feature;
    }
}
