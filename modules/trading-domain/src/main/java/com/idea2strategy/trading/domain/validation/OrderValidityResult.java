package com.idea2strategy.trading.domain.validation;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public record OrderValidityResult(
        UUID proposalId,
        OrderValidityStatus status,
        List<OrderValidityReason> reasons,
        String fundsSnapshotVersion,
        String instrumentPolicyVersion,
        List<RiskPolicyEvidence> riskPolicyEvidence) {

    public OrderValidityResult {
        proposalId = OrderValidityInputValidation.requireNonNull(proposalId, "proposalId");
        status = OrderValidityInputValidation.requireNonNull(status, "status");
        reasons = OrderValidityInputValidation.immutableList(reasons, "reasons");
        OrderValidityInputValidation.requireStrictlySorted(reasons, Comparator.naturalOrder(), "reasons");
        fundsSnapshotVersion = OrderValidityInputValidation.requireNonBlank(
                fundsSnapshotVersion, "fundsSnapshotVersion");
        if (instrumentPolicyVersion != null) {
            instrumentPolicyVersion = OrderValidityInputValidation.requireNonBlank(
                    instrumentPolicyVersion, "instrumentPolicyVersion");
        }
        riskPolicyEvidence = OrderValidityInputValidation.immutableList(
                riskPolicyEvidence, "riskPolicyEvidence");
        OrderValidityInputValidation.requireStrictlySorted(riskPolicyEvidence,
                Comparator.comparing(RiskPolicyEvidence::metricCode)
                        .thenComparing(RiskPolicyEvidence::policyVersion),
                "riskPolicyEvidence");
        if (status == OrderValidityStatus.ACCEPTED && !reasons.isEmpty()) {
            throw new IllegalArgumentException("accepted results must not contain reasons");
        }
        if (status != OrderValidityStatus.ACCEPTED && reasons.isEmpty()) {
            throw new IllegalArgumentException("non-accepted results must contain at least one reason");
        }
    }
}
