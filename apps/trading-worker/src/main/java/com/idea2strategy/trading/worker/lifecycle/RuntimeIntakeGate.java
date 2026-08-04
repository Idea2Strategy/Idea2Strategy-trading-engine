package com.idea2strategy.trading.worker.lifecycle;

import java.time.Duration;
import java.util.Objects;

/**
 * Process-wide admission gate for work that can mutate trading state.
 *
 * <p>The worker starts closed. Startup recovery opens it only after durable stop settlements have
 * been reconciled. Shutdown closes it before waiting for an already admitted polling cycle, so no
 * new command or market event starts while final recovery is running.
 */
public final class RuntimeIntakeGate {
    private boolean open;
    private int activeCycles;

    public boolean runIfOpen(Runnable cycle) {
        Objects.requireNonNull(cycle, "cycle");
        synchronized (this) {
            if (!open) {
                return false;
            }
            activeCycles++;
        }
        try {
            cycle.run();
            return true;
        } finally {
            synchronized (this) {
                activeCycles--;
                notifyAll();
            }
        }
    }

    public synchronized void openAfterRecovery() {
        open = true;
    }

    public synchronized void beginDrain() {
        open = false;
    }

    public synchronized boolean awaitDrained(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        long remainingNanos = timeout.toNanos();
        long deadline = System.nanoTime() + remainingNanos;
        while (activeCycles > 0 && remainingNanos > 0) {
            try {
                long millis = remainingNanos / 1_000_000L;
                int nanos = (int) (remainingNanos % 1_000_000L);
                wait(millis, nanos);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
            remainingNanos = deadline - System.nanoTime();
        }
        return activeCycles == 0;
    }
}
