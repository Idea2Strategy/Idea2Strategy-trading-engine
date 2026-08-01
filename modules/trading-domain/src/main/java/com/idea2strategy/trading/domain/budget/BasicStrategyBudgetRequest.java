package com.idea2strategy.trading.domain.budget;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public record BasicStrategyBudgetRequest(
        UUID strategyId,
        BigDecimal maximumEquityRatio,
        BigDecimal currentPositionMarketValue,
        BigDecimal reservedCash,
        boolean positionValuationComplete,
        BasicSizingPolicy sizingPolicy,
        List<UUID> candidateIds) {

    public BasicStrategyBudgetRequest {
        BudgetInputValidation.requireNonNull(strategyId, "strategyId");
        requireRatio(maximumEquityRatio, "maximumEquityRatio");
        requireNonNegative(currentPositionMarketValue, "currentPositionMarketValue");
        requireNonNegative(reservedCash, "reservedCash");
        BudgetInputValidation.requireNonNull(sizingPolicy, "sizingPolicy");
        candidateIds = BudgetInputValidation.immutableList(candidateIds, "candidateIds");
        if (candidateIds.size() != new HashSet<>(candidateIds).size()) {
            throw new IllegalArgumentException("candidateIds must not contain duplicates");
        }
    }

    private static void requireRatio(BigDecimal value, String name) {
        BudgetInputValidation.requireNonNull(value, name);
        if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(name + " must be between zero and one");
        }
    }

    private static void requireNonNegative(BigDecimal value, String name) {
        BudgetInputValidation.requireNonNull(value, name);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
