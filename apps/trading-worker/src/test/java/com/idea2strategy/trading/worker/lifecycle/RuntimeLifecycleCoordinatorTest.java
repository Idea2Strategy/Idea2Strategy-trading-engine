package com.idea2strategy.trading.worker.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.trading.common.runtime.FileReadinessMarker;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeLifecycleCoordinatorTest {

    private static final Instant NOW = Instant.parse("2026-08-04T13:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void opensIntakeOnlyAfterStartupRecovery() {
        RuntimeIntakeGate gate = new RuntimeIntakeGate();
        Path readinessFile = temporaryDirectory.resolve("startup.ready");
        List<Instant> recoveries = new ArrayList<>();
        RuntimeLifecycleCoordinator coordinator = coordinator(gate, readinessFile, now -> {
            assertThat(gate.runIfOpen(() -> {})).isFalse();
            recoveries.add(now);
        });

        coordinator.startup();

        assertThat(recoveries).containsExactly(NOW);
        assertThat(gate.runIfOpen(() -> {})).isTrue();
        assertThat(readinessFile).exists();
    }

    @Test
    void failedStartupRecoveryLeavesIntakeClosed() {
        RuntimeIntakeGate gate = new RuntimeIntakeGate();
        Path readinessFile = temporaryDirectory.resolve("failed-startup.ready");
        RuntimeLifecycleCoordinator coordinator = coordinator(gate, readinessFile, ignored -> {
            throw new IllegalStateException("database unavailable");
        });

        assertThatThrownBy(coordinator::startup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database unavailable");
        assertThat(gate.runIfOpen(() -> {})).isFalse();
        assertThat(readinessFile).doesNotExist();
    }

    @Test
    void shutdownClosesIntakeThenRunsFinalRecovery() {
        RuntimeIntakeGate gate = new RuntimeIntakeGate();
        Path readinessFile = temporaryDirectory.resolve("shutdown.ready");
        List<Instant> recoveries = new ArrayList<>();
        RuntimeLifecycleCoordinator coordinator = coordinator(gate, readinessFile, now -> {
            if (!recoveries.isEmpty()) {
                assertThat(gate.runIfOpen(() -> {})).isFalse();
            }
            recoveries.add(now);
        });
        coordinator.startup();
        assertThat(readinessFile).exists();

        coordinator.shutdown();

        assertThat(recoveries).containsExactly(NOW, NOW);
        assertThat(gate.runIfOpen(() -> {})).isFalse();
        assertThat(Files.exists(readinessFile)).isFalse();
    }

    private static RuntimeLifecycleCoordinator coordinator(
            RuntimeIntakeGate gate,
            Path readinessFile,
            RuntimeLifecycleCoordinator.Recovery recovery) {
        return new RuntimeLifecycleCoordinator(
                gate,
                new FileReadinessMarker(readinessFile),
                recovery,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(1));
    }
}
