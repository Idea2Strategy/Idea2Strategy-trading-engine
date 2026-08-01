package com.idea2strategy.trading.domain.budget;

import java.math.BigDecimal;

public record BasicSizingPolicy(BasicSizingMode mode, BigDecimal value) {
    public BasicSizingPolicy {
        BudgetInputValidation.requireNonNull(mode, "mode");
        BudgetInputValidation.requireNonNull(value, "value");
        if (value.signum() < 0 || (mode == BasicSizingMode.AVAILABLE_BUDGET_RATIO
                && value.compareTo(BigDecimal.ONE) > 0)) {
            throw new IllegalArgumentException("invalid Basic sizing value");
        }
    }

    public static BasicSizingPolicy fixedAmount(BigDecimal amount) {
        return new BasicSizingPolicy(BasicSizingMode.FIXED_AMOUNT, amount);
    }

    public static BasicSizingPolicy availableBudgetRatio(BigDecimal ratio) {
        return new BasicSizingPolicy(BasicSizingMode.AVAILABLE_BUDGET_RATIO, ratio);
    }
}
