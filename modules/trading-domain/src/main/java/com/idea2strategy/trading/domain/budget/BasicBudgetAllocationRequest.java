package com.idea2strategy.trading.domain.budget;

import java.math.BigDecimal;
import java.util.List;

public record BasicBudgetAllocationRequest(
        BigDecimal totalEquity,
        BigDecimal grossAvailableCash,
        BigDecimal sharedReservedCash,
        ExpectedCostPolicy costPolicy,
        List<BasicStrategyBudgetRequest> strategies) {

    public BasicBudgetAllocationRequest {
        requireNonNegative(totalEquity, "totalEquity");
        requireNonNegative(grossAvailableCash, "grossAvailableCash");
        requireNonNegative(sharedReservedCash, "sharedReservedCash");
        BudgetInputValidation.requireNonNull(costPolicy, "costPolicy");
        strategies = BudgetInputValidation.immutableList(strategies, "strategies");
        if (strategies.isEmpty()) {
            throw new IllegalArgumentException("strategies must not be empty");
        }
        if (strategies.size() != strategies.stream().map(BasicStrategyBudgetRequest::strategyId)
                .collect(java.util.stream.Collectors.toSet()).size()) {
            throw new IllegalArgumentException("strategies must not contain duplicate strategy IDs");
        }
    }

    private static void requireNonNegative(BigDecimal value, String name) {
        BudgetInputValidation.requireNonNull(value, name);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
