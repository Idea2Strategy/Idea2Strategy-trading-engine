package com.idea2strategy.trading.strategy.runtime.control;

import com.idea2strategy.trading.strategy.runtime.warmup.PreparedWarmup;
import com.idea2strategy.trading.strategy.runtime.warmup.WarmupRequest;
import java.util.function.Consumer;

@FunctionalInterface
public interface BotStartupGate {
    PreparedWarmup start(WarmupRequest request, Consumer<PreparedWarmup> starter);
}
