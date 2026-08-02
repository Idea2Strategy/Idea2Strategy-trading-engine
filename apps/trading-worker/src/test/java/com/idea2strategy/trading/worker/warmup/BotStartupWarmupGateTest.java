package com.idea2strategy.trading.worker.warmup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.idea2strategy.trading.strategy.runtime.warmup.StartupWarmupCoordinator;
import com.idea2strategy.trading.strategy.runtime.warmup.WarmupException;
import com.idea2strategy.trading.strategy.runtime.warmup.WarmupFailure;
import com.idea2strategy.trading.strategy.runtime.warmup.WarmupRequest;
import com.idea2strategy.trading.strategy.runtime.warmup.WarmupRequirement;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class BotStartupWarmupGateTest {
    @Test
    void neverStartsTheBotWhenWarmupCannotBePrepared() {
        BotStartupWarmupGate gate = new BotStartupWarmupGate(
                new StartupWarmupCoordinator(ignored -> Optional.empty(), "1", "feature-object-v1"));
        AtomicBoolean started = new AtomicBoolean();

        WarmupException exception = assertThrows(WarmupException.class,
                () -> gate.start(request(), ignored -> started.set(true)));

        assertEquals(WarmupFailure.SNAPSHOT_NOT_FOUND, exception.failure());
        assertFalse(started.get());
    }

    private static WarmupRequest request() {
        return new WarmupRequest(
                UUID.fromString("b274523a-e318-4b7b-81bd-d3458738a690"),
                UUID.fromString("314d3ed1-b7ca-4432-94e3-a13b53ed122d"),
                Instant.parse("2026-07-31T14:31:00Z"),
                Set.of(new WarmupRequirement(
                        "close-entry", "close", "1.0.0",
                        Set.of("8a35e6b5-cf84-4f63-920d-57c1f1b95df0"), "PT1M", 1)));
    }
}
