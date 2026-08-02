package com.idea2strategy.trading.worker.stop;

import com.idea2strategy.trading.application.stop.BotStopOrchestrator;
import java.time.Clock;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

public final class BotStopRecoveryWorker {
    private final BotStopOrchestrator orchestrator;
    private final Clock clock;

    public BotStopRecoveryWorker(BotStopOrchestrator orchestrator) {
        this(orchestrator, Clock.systemUTC());
    }

    BotStopRecoveryWorker(BotStopOrchestrator orchestrator, Clock clock) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Scheduled(fixedDelayString = "${trading.stop.recovery-delay:PT5S}")
    public void recover() {
        orchestrator.resumeRecoverable(clock.instant());
    }
}
