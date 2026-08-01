package com.idea2strategy.trading.strategy.runtime.warmup;

import java.util.Optional;

@FunctionalInterface
public interface WarmupDataSource {
    Optional<WarmupDataSnapshot> load(WarmupRequest request);
}
