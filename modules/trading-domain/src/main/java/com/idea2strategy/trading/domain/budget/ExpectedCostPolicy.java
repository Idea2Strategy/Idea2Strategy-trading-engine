package com.idea2strategy.trading.domain.budget;

import java.math.BigDecimal;
import java.util.Objects;

public record ExpectedCostPolicy(
        String version, BigDecimal feeRate, BigDecimal adverseBuySlippageRate) {

    public ExpectedCostPolicy {
        Objects.requireNonNull(version, "version");
        requireNonNegative(feeRate, "feeRate");
        requireNonNegative(adverseBuySlippageRate, "adverseBuySlippageRate");
        if (version.isBlank()) {
            throw new IllegalArgumentException("cost policy version must not be blank");
        }
    }

    private static void requireNonNegative(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
