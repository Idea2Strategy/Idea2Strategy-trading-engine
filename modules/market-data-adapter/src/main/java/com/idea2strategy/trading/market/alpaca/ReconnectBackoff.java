package com.idea2strategy.trading.market.alpaca;

import java.time.Duration;
import java.util.Objects;

public final class ReconnectBackoff {
    private final Duration initialDelay;
    private final Duration maximumDelay;

    public ReconnectBackoff(Duration initialDelay, Duration maximumDelay) {
        this.initialDelay = requirePositive(initialDelay, "initialDelay");
        this.maximumDelay = requirePositive(maximumDelay, "maximumDelay");
        if (initialDelay.compareTo(maximumDelay) > 0) {
            throw new IllegalArgumentException("initialDelay must not exceed maximumDelay");
        }
    }

    public Duration delayForAttempt(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
        long multiplier = 1L << Math.min(attempt - 1, 62);
        try {
            Duration delay = initialDelay.multipliedBy(multiplier);
            return delay.compareTo(maximumDelay) > 0 ? maximumDelay : delay;
        } catch (ArithmeticException ignored) {
            return maximumDelay;
        }
    }

    private static Duration requirePositive(Duration duration, String name) {
        Duration value = Objects.requireNonNull(duration, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
