package com.idea2strategy.trading.worker.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuntimeLifecycleCoordinatorTest {

    private static final Instant NOW = Instant.parse("2026-08-04T13:00:00Z");

    @Test
    void opensIntakeOnlyAfterStartupRecovery() {
        RuntimeIntakeGate gate = new RuntimeIntakeGate();
        List<Instant> recoveries = new ArrayList<>();
        RuntimeLifecycleCoordinator coordinator = coordinator(gate, now -> {
            assertThat(gate.runIfOpen(() -> {})).isFalse();
            recoveries.add(now);
        });

        coordinator.startup();

        assertThat(recoveries).containsExactly(NOW);
        assertThat(gate.runIfOpen(() -> {})).isTrue();
    }

    @Test
    void failedStartupRecoveryLeavesIntakeClosed() {
        RuntimeIntakeGate gate = new RuntimeIntakeGate();
        RuntimeLifecycleCoordinator coordinator = coordinator(gate, ignored -> {
            throw new IllegalStateException("database unavailable");
        });

        assertThatThrownBy(coordinator::startup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database unavailable");
        assertThat(gate.runIfOpen(() -> {})).isFalse();
    }

    @Test
    void shutdownClosesIntakeThenRunsFinalRecovery() {
        RuntimeIntakeGate gate = new RuntimeIntakeGate();
        List<Instant> recoveries = new ArrayList<>();
        RuntimeLifecycleCoordinator coordinator = coordinator(gate, now -> {
            if (!recoveries.isEmpty()) {
                assertThat(gate.runIfOpen(() -> {})).isFalse();
            }
            recoveries.add(now);
        });
        coordinator.startup();

        coordinator.shutdown();

        assertThat(recoveries).containsExactly(NOW, NOW);
        assertThat(gate.runIfOpen(() -> {})).isFalse();
    }

    private static RuntimeLifecycleCoordinator coordinator(
            RuntimeIntakeGate gate, RuntimeLifecycleCoordinator.Recovery recovery) {
        return new RuntimeLifecycleCoordinator(
                gate,
                recovery,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(1));
    }
}
