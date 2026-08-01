package com.idea2strategy.trading.domain.budget;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record BasicBudgetAllocationResult(
        BigDecimal spendableCash,
        List<BasicBudgetDecision> decisions) {

    public BasicBudgetAllocationResult {
        Objects.requireNonNull(spendableCash, "spendableCash");
        if (spendableCash.signum() < 0) {
            throw new IllegalArgumentException("spendableCash must not be negative");
        }
        decisions = List.copyOf(Objects.requireNonNull(decisions, "decisions"));
    }
}
