package com.idea2strategy.trading.worker.lifecycle;

import com.idea2strategy.trading.common.runtime.FileReadinessMarker;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;

/** Orders durable recovery around runtime intake for restart- and shutdown-safe operation. */
public final class RuntimeLifecycleCoordinator {
    private final RuntimeIntakeGate gate;
    private final FileReadinessMarker readinessMarker;
    private final Recovery recovery;
    private final Clock clock;
    private final Duration drainTimeout;

    RuntimeLifecycleCoordinator(
            RuntimeIntakeGate gate,
            FileReadinessMarker readinessMarker,
            Recovery recovery,
            Clock clock,
            Duration drainTimeout) {
        this.gate = Objects.requireNonNull(gate, "gate");
        this.readinessMarker = Objects.requireNonNull(readinessMarker, "readinessMarker");
        this.recovery = Objects.requireNonNull(recovery, "recovery");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.drainTimeout = requirePositive(drainTimeout, "drainTimeout");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startup() {
        recovery.reconcile(clock.instant());
        gate.openAfterRecovery();
        readinessMarker.markReady();
    }

    @EventListener(ContextClosedEvent.class)
    public void shutdown() {
        gate.beginDrain();
        readinessMarker.markNotReady();
        if (!gate.awaitDrained(drainTimeout)) {
            throw new IllegalStateException(
                    "trading runtime intake did not drain within " + drainTimeout);
        }
        recovery.reconcile(clock.instant());
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    @FunctionalInterface
    interface Recovery {
        void reconcile(Instant now);
    }
}
