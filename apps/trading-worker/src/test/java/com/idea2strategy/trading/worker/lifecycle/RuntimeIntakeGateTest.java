package com.idea2strategy.trading.worker.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RuntimeIntakeGateTest {

    @Test
    void staysClosedUntilStartupReconciliationCompletes() {
        RuntimeIntakeGate gate = new RuntimeIntakeGate();

        assertThat(gate.runIfOpen(() -> {})).isFalse();

        gate.openAfterRecovery();

        assertThat(gate.runIfOpen(() -> {})).isTrue();
    }

    @Test
    void drainRejectsNewIntakeAndWaitsForTheActiveCycle() throws Exception {
        RuntimeIntakeGate gate = new RuntimeIntakeGate();
        gate.openAfterRecovery();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<Boolean> active = executor.submit(() -> gate.runIfOpen(() -> {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();

            gate.beginDrain();

            assertThat(gate.runIfOpen(() -> {})).isFalse();
            assertThat(gate.awaitDrained(Duration.ofMillis(20))).isFalse();
            release.countDown();
            assertThat(active.get(1, TimeUnit.SECONDS)).isTrue();
            assertThat(gate.awaitDrained(Duration.ofSeconds(1))).isTrue();
        }
    }
}
