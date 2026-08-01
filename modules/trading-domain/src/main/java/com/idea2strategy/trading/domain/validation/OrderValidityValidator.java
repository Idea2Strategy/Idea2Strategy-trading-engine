package com.idea2strategy.trading.domain.validation;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

public final class OrderValidityValidator {

    public OrderValidityResult validate(OrderValidityRequest request) {
        EnumSet<OrderValidityReason> reasons = EnumSet.noneOf(OrderValidityReason.class);

        validateFunds(request, reasons);
        validateRisk(request, reasons);
        validateInstrument(request, reasons);

        List<RiskPolicyEvidence> evidence = request.riskEvaluations().stream()
                .map(evaluation -> new RiskPolicyEvidence(evaluation.metricCode(), evaluation.policyVersion()))
                .sorted(Comparator.comparing(RiskPolicyEvidence::metricCode)
                        .thenComparing(RiskPolicyEvidence::policyVersion))
                .toList();
        List<OrderValidityReason> sortedReasons = reasons.stream().sorted().toList();

        return new OrderValidityResult(
                request.proposalId(),
                OrderValidityStatus.forReasons(sortedReasons),
                sortedReasons,
                fundsSnapshotVersion(request),
                instrumentPolicyVersion(request),
                evidence);
    }

    private static void validateFunds(OrderValidityRequest request, EnumSet<OrderValidityReason> reasons) {
        AvailableFundsSnapshot latestFunds = request.latestFundsSnapshot();
        if (latestFunds == null) {
            reasons.add(OrderValidityReason.AVAILABLE_FUNDS_UNAVAILABLE);
        } else if (!latestFunds.version().equals(request.allocationFundsSnapshotVersion())) {
            reasons.add(OrderValidityReason.AVAILABLE_FUNDS_SNAPSHOT_CHANGED);
        } else if (request.totalRequiredCash().compareTo(latestFunds.availableCash()) > 0) {
            reasons.add(OrderValidityReason.INSUFFICIENT_AVAILABLE_FUNDS);
        }
    }

    private static void validateRisk(OrderValidityRequest request, EnumSet<OrderValidityReason> reasons) {
        if (!request.riskEvaluationComplete()) {
            reasons.add(OrderValidityReason.RISK_EVALUATION_UNAVAILABLE);
        }
        for (RiskMetricEvaluation evaluation : request.riskEvaluations()) {
            if (request.riskDirection() == RiskDirection.INCREASING
                    && evaluation.projectedValue().compareTo(evaluation.maximumAllowedValue()) > 0) {
                reasons.add(OrderValidityReason.RISK_LIMIT_EXCEEDED);
            }
            if (request.riskDirection() == RiskDirection.REDUCING
                    && evaluation.projectedValue().compareTo(evaluation.currentValue()) > 0) {
                reasons.add(OrderValidityReason.RISK_REDUCTION_NOT_CONFIRMED);
            }
        }
    }

    private static void validateInstrument(OrderValidityRequest request, EnumSet<OrderValidityReason> reasons) {
        InstrumentNumericPolicy policy = request.instrumentPolicy();
        if (policy == null) {
            reasons.add(OrderValidityReason.INSTRUMENT_POLICY_UNAVAILABLE);
            return;
        }
        if (request.quantity().compareTo(policy.minimumQuantity()) < 0) {
            reasons.add(OrderValidityReason.MINIMUM_QUANTITY_NOT_MET);
        }
        if (request.quantity().multiply(request.price()).compareTo(policy.minimumNotional()) < 0) {
            reasons.add(OrderValidityReason.MINIMUM_NOTIONAL_NOT_MET);
        }
        if (OrderValidityInputValidation.normalizedScale(request.quantity()) > policy.maximumQuantityScale()) {
            reasons.add(OrderValidityReason.QUANTITY_PRECISION_EXCEEDED);
        }
        if (OrderValidityInputValidation.normalizedScale(request.price()) > policy.maximumPriceScale()) {
            reasons.add(OrderValidityReason.PRICE_PRECISION_EXCEEDED);
        }
    }

    private static String fundsSnapshotVersion(OrderValidityRequest request) {
        AvailableFundsSnapshot latestFunds = request.latestFundsSnapshot();
        return latestFunds == null ? request.allocationFundsSnapshotVersion() : latestFunds.version();
    }

    private static String instrumentPolicyVersion(OrderValidityRequest request) {
        InstrumentNumericPolicy policy = request.instrumentPolicy();
        return policy == null ? null : policy.version();
    }

}
