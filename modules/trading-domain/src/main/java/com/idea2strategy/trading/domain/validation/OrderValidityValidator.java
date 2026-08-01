package com.idea2strategy.trading.domain.validation;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

public final class OrderValidityValidator {

    public OrderValidityResult validate(OrderValidityRequest request) {
        EnumSet<OrderValidityReason> reasons = EnumSet.noneOf(OrderValidityReason.class);

        validateFunds(request, reasons);
        validateRisk(request, reasons);

        List<RiskPolicyEvidence> evidence = request.riskEvaluations().stream()
                .map(evaluation -> new RiskPolicyEvidence(evaluation.metricCode(), evaluation.policyVersion()))
                .sorted(Comparator.comparing(RiskPolicyEvidence::metricCode)
                        .thenComparing(RiskPolicyEvidence::policyVersion))
                .toList();
        List<OrderValidityReason> sortedReasons = reasons.stream().sorted().toList();

        return new OrderValidityResult(
                request.proposalId(),
                statusFor(reasons),
                sortedReasons,
                fundsSnapshotVersion(request),
                request.instrumentPolicy().version(),
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

    private static String fundsSnapshotVersion(OrderValidityRequest request) {
        AvailableFundsSnapshot latestFunds = request.latestFundsSnapshot();
        return latestFunds == null ? request.allocationFundsSnapshotVersion() : latestFunds.version();
    }

    private static OrderValidityStatus statusFor(EnumSet<OrderValidityReason> reasons) {
        if (reasons.contains(OrderValidityReason.RISK_EVALUATION_UNAVAILABLE)
                || reasons.contains(OrderValidityReason.RISK_LIMIT_EXCEEDED)
                || reasons.contains(OrderValidityReason.RISK_REDUCTION_NOT_CONFIRMED)) {
            return OrderValidityStatus.REJECTED;
        }
        return reasons.isEmpty() ? OrderValidityStatus.ACCEPTED : OrderValidityStatus.REEVALUATION_REQUIRED;
    }
}
