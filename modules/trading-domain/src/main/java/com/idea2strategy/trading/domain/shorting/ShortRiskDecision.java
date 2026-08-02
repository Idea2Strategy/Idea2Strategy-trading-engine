package com.idea2strategy.trading.domain.shorting;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ShortRiskDecision(
        UUID assessmentId,
        ShortRiskDecisionStatus status,
        List<ShortRiskReasonCode> reasonCodes,
        BigDecimal marketValue,
        BigDecimal initialMarginRequired,
        BigDecimal maintenanceMarginRequired,
        String marginPolicyVersion,
        String eligibilitySnapshotVersion,
        Instant assessedAt) {
    public ShortRiskDecision {
        assessmentId = ShortInputs.required(assessmentId, "assessmentId");
        status = ShortInputs.required(status, "status");
        reasonCodes = List.copyOf(ShortInputs.required(reasonCodes, "reasonCodes"));
        marketValue = ShortInputs.nonNegative(marketValue, "marketValue");
        initialMarginRequired = ShortInputs.nonNegative(initialMarginRequired, "initialMarginRequired");
        maintenanceMarginRequired = ShortInputs.nonNegative(maintenanceMarginRequired, "maintenanceMarginRequired");
        marginPolicyVersion = ShortInputs.text(marginPolicyVersion, "marginPolicyVersion");
        eligibilitySnapshotVersion = ShortInputs.text(eligibilitySnapshotVersion, "eligibilitySnapshotVersion");
        assessedAt = ShortInputs.required(assessedAt, "assessedAt");
        if ((status == ShortRiskDecisionStatus.APPROVED) != reasonCodes.isEmpty()) {
            throw new IllegalArgumentException("approved decisions must have no reasons and rejected decisions must have reasons");
        }
    }
}
