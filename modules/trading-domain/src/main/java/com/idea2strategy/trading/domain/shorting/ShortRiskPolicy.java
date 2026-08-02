package com.idea2strategy.trading.domain.shorting;

import java.math.BigDecimal;
import java.util.ArrayList;

public final class ShortRiskPolicy {
    public ShortRiskDecision assess(ShortRiskAssessmentRequest request) {
        ShortInputs.required(request, "request");
        BigDecimal marketValue = request.quantity().multiply(request.executionPrice());
        BigDecimal initial = marketValue.multiply(request.marginPolicy().initialMarginRate());
        BigDecimal maintenance = marketValue.multiply(request.marginPolicy().maintenanceMarginRate());
        var reasons = new ArrayList<ShortRiskReasonCode>();
        if (request.increasesShortExposure() && !ShortInputs.isInteger(request.quantity())) {
            reasons.add(ShortRiskReasonCode.FRACTIONAL_SHORT_INCREASE_NOT_ALLOWED);
        }
        if (!request.eligibilitySnapshot().borrowable()) reasons.add(ShortRiskReasonCode.INSTRUMENT_NOT_BORROWABLE);
        if (!request.eligibilitySnapshot().easyToBorrow()) reasons.add(ShortRiskReasonCode.INSTRUMENT_NOT_EASY_TO_BORROW);
        if (!request.eligibilitySnapshot().hasLocateEvidence()) reasons.add(ShortRiskReasonCode.LOCATE_EVIDENCE_MISSING);
        if (request.availableBuyingPower().compareTo(initial) < 0) {
            reasons.add(ShortRiskReasonCode.INSUFFICIENT_BUYING_POWER);
        }
        if (request.remainingPortfolioRiskCapacity().compareTo(initial) < 0) {
            reasons.add(ShortRiskReasonCode.PORTFOLIO_RISK_LIMIT_EXCEEDED);
        }
        return new ShortRiskDecision(request.assessmentId(), reasons.isEmpty()
                ? ShortRiskDecisionStatus.APPROVED : ShortRiskDecisionStatus.REJECTED,
                reasons, marketValue, initial, maintenance, request.marginPolicy().policyVersion(),
                request.eligibilitySnapshot().snapshotVersion(), request.assessedAt());
    }
}
