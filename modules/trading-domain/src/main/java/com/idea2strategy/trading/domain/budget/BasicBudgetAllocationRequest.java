package com.idea2strategy.trading.domain.budget;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

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
        Objects.requireNonNull(costPolicy, "costPolicy");
        strategies = List.copyOf(Objects.requireNonNull(strategies, "strategies"));
        if (strategies.size() != strategies.stream().map(BasicStrategyBudgetRequest::strategyId)
                .collect(java.util.stream.Collectors.toSet()).size()) {
            throw new IllegalArgumentException("strategies must not contain duplicate strategy IDs");
        }
    }

    private static void requireNonNegative(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
