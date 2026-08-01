package com.idea2strategy.trading.domain.budget;

import java.math.BigDecimal;

public record ExpectedCostPolicy(
        String version, BigDecimal feeRate, BigDecimal adverseBuySlippageRate) {

    public ExpectedCostPolicy {
        BudgetInputValidation.requireNonNull(version, "version");
        requireRate(feeRate, "feeRate");
        requireRate(adverseBuySlippageRate, "adverseBuySlippageRate");
        if (version.isBlank()) {
            throw new IllegalArgumentException("cost policy version must not be blank");
        }
    }

    private static void requireRate(BigDecimal value, String name) {
        BudgetInputValidation.requireNonNull(value, name);
        if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(name + " must be between zero and one");
        }
    }
}
