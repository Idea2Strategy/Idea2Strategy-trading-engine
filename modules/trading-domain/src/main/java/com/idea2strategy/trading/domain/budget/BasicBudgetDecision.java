package com.idea2strategy.trading.domain.budget;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record BasicBudgetDecision(
        UUID strategyId,
        UUID candidateId,
        BigDecimal requestedCash,
        BigDecimal approvedPrincipal,
        BigDecimal expectedSlippage,
        BigDecimal expectedFee,
        BigDecimal totalRequiredCash,
        BudgetDecisionStatus status,
        List<BudgetReasonCode> reasonCodes,
        String costPolicyVersion) {

    public BasicBudgetDecision {
        Objects.requireNonNull(strategyId, "strategyId");
        Objects.requireNonNull(candidateId, "candidateId");
        requireNonNegative(requestedCash, "requestedCash");
        requireNonNegative(approvedPrincipal, "approvedPrincipal");
        requireNonNegative(expectedSlippage, "expectedSlippage");
        requireNonNegative(expectedFee, "expectedFee");
        requireNonNegative(totalRequiredCash, "totalRequiredCash");
        Objects.requireNonNull(status, "status");
        reasonCodes = List.copyOf(Objects.requireNonNull(reasonCodes, "reasonCodes"));
        Objects.requireNonNull(costPolicyVersion, "costPolicyVersion");
        if (costPolicyVersion.isBlank()) {
            throw new IllegalArgumentException("costPolicyVersion must not be blank");
        }
    }

    private static void requireNonNegative(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
