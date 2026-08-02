package com.idea2strategy.trading.domain.shorting;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ShortRiskAssessmentRequest(
        UUID assessmentId,
        UUID botId,
        UUID instrumentId,
        boolean increasesShortExposure,
        BigDecimal quantity,
        BigDecimal executionPrice,
        BigDecimal availableBuyingPower,
        BigDecimal remainingPortfolioRiskCapacity,
        ShortEligibilitySnapshot eligibilitySnapshot,
        MarginPolicy marginPolicy,
        Instant assessedAt) {
    public ShortRiskAssessmentRequest {
        assessmentId = ShortInputs.required(assessmentId, "assessmentId");
        botId = ShortInputs.required(botId, "botId");
        instrumentId = ShortInputs.required(instrumentId, "instrumentId");
        quantity = ShortInputs.positive(quantity, "quantity");
        executionPrice = ShortInputs.positive(executionPrice, "executionPrice");
        availableBuyingPower = ShortInputs.nonNegative(availableBuyingPower, "availableBuyingPower");
        remainingPortfolioRiskCapacity = ShortInputs.nonNegative(
                remainingPortfolioRiskCapacity, "remainingPortfolioRiskCapacity");
        eligibilitySnapshot = ShortInputs.required(eligibilitySnapshot, "eligibilitySnapshot");
        marginPolicy = ShortInputs.required(marginPolicy, "marginPolicy");
        assessedAt = ShortInputs.required(assessedAt, "assessedAt");
        if (!instrumentId.equals(eligibilitySnapshot.instrumentId())) {
            throw new IllegalArgumentException("eligibility snapshot belongs to another instrument");
        }
        if (eligibilitySnapshot.observedAt().isAfter(assessedAt)) {
            throw new IllegalArgumentException("eligibility snapshot must not come from the future");
        }
    }
}
