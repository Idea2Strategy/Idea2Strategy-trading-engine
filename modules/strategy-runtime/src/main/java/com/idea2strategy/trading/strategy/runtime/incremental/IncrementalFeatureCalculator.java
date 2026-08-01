package com.idea2strategy.trading.strategy.runtime.incremental;

public interface IncrementalFeatureCalculator {
    FeatureKey key();

    IncrementalFeatureState calculate(IncrementalFeatureState current, RuntimeTrigger trigger);
}
