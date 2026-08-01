package com.idea2strategy.trading.domain.validation;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public record OrderValidityRequest(
        UUID proposalId,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal totalRequiredCash,
        String allocationFundsSnapshotVersion,
        AvailableFundsSnapshot latestFundsSnapshot,
        RiskDirection riskDirection,
        boolean riskEvaluationComplete,
        List<RiskMetricEvaluation> riskEvaluations,
        InstrumentNumericPolicy instrumentPolicy) {

    public OrderValidityRequest {
        proposalId = OrderValidityInputValidation.requireNonNull(proposalId, "proposalId");
        quantity = OrderValidityInputValidation.requireSupportedDecimal(
                OrderValidityInputValidation.requireNonNegative(quantity, "quantity"), "quantity");
        price = OrderValidityInputValidation.requireSupportedDecimal(
                OrderValidityInputValidation.requireNonNegative(price, "price"), "price");
        OrderValidityInputValidation.requireSupportedProduct(quantity, price, "quantity multiplied by price");
        totalRequiredCash = OrderValidityInputValidation.requireNonNegative(totalRequiredCash, "totalRequiredCash");
        allocationFundsSnapshotVersion = OrderValidityInputValidation.requireNonBlank(
                allocationFundsSnapshotVersion, "allocationFundsSnapshotVersion");
        riskDirection = OrderValidityInputValidation.requireNonNull(riskDirection, "riskDirection");
        riskEvaluations = OrderValidityInputValidation.immutableList(riskEvaluations, "riskEvaluations");
        if (riskEvaluations.size() != riskEvaluations.stream()
                .map(RiskMetricEvaluation::metricCode)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new))
                .size()) {
            throw new IllegalArgumentException("riskEvaluations must not contain duplicate metric codes");
        }
    }
}
