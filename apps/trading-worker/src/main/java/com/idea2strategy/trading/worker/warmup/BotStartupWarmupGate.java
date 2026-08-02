package com.idea2strategy.trading.worker.warmup;

import com.idea2strategy.trading.strategy.runtime.warmup.PreparedWarmup;
import com.idea2strategy.trading.strategy.runtime.warmup.StartupWarmupCoordinator;
import com.idea2strategy.trading.strategy.runtime.warmup.WarmupRequest;
import java.util.Objects;
import java.util.function.Consumer;

public final class BotStartupWarmupGate {
    private final StartupWarmupCoordinator coordinator;

    public BotStartupWarmupGate(StartupWarmupCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    public PreparedWarmup start(WarmupRequest request, Consumer<PreparedWarmup> starter) {
        Objects.requireNonNull(starter, "starter");
        PreparedWarmup prepared = coordinator.prepare(request);
        starter.accept(prepared);
        return prepared;
    }
}
