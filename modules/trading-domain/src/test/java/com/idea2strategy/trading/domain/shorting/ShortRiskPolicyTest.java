package com.idea2strategy.trading.domain.shorting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ShortRiskPolicyTest {
    private static final UUID INSTRUMENT_ID = UUID.randomUUID();
    private static final Instant OBSERVED_AT = Instant.parse("2026-08-02T14:00:00Z");
    private final ShortRiskPolicy policy = new ShortRiskPolicy();

    @Test
    void approvesAnEligibleIntegerShortWhenBuyingPowerAndRiskCapacityCoverInitialMargin() {
        ShortRiskDecision decision = policy.assess(request(
                new BigDecimal("10"), new BigDecimal("25"), new BigDecimal("500"), new BigDecimal("200"), eligible()));

        assertEquals(ShortRiskDecisionStatus.APPROVED, decision.status());
        assertEquals(List.of(), decision.reasonCodes());
        assertEquals(new BigDecimal("250"), decision.marketValue());
        assertEquals(0, new BigDecimal("125.00").compareTo(decision.initialMarginRequired()));
        assertEquals(0, new BigDecimal("75.00").compareTo(decision.maintenanceMarginRequired()));
        assertEquals("margin-us-equity-v1", decision.marginPolicyVersion());
        assertEquals("borrow-feed-2026-08-02T14:00Z", decision.eligibilitySnapshotVersion());
    }

    @Test
    void rejectsIncreasingFractionalShortAndReportsEveryStablePolicyFailureInDefinedOrder() {
        ShortEligibilitySnapshot unavailable = new ShortEligibilitySnapshot(
                INSTRUMENT_ID, false, false, null, "borrow-feed-v9", OBSERVED_AT);
        ShortRiskDecision decision = policy.assess(request(
                new BigDecimal("1.5"), new BigDecimal("100"), new BigDecimal("10"), new BigDecimal("20"), unavailable));

        assertEquals(ShortRiskDecisionStatus.REJECTED, decision.status());
        assertEquals(List.of(
                ShortRiskReasonCode.FRACTIONAL_SHORT_INCREASE_NOT_ALLOWED,
                ShortRiskReasonCode.INSTRUMENT_NOT_BORROWABLE,
                ShortRiskReasonCode.INSTRUMENT_NOT_EASY_TO_BORROW,
                ShortRiskReasonCode.LOCATE_EVIDENCE_MISSING,
                ShortRiskReasonCode.INSUFFICIENT_BUYING_POWER,
                ShortRiskReasonCode.PORTFOLIO_RISK_LIMIT_EXCEEDED), decision.reasonCodes());
    }

    @Test
    void doesNotApplyIntegerRuleWhenTheOrderOnlyReducesExistingShortExposure() {
        ShortRiskAssessmentRequest request = new ShortRiskAssessmentRequest(
                UUID.randomUUID(), UUID.randomUUID(), INSTRUMENT_ID, false,
                new BigDecimal("0.5"), new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("100"),
                eligible(), marginPolicy(), OBSERVED_AT);

        assertEquals(ShortRiskDecisionStatus.APPROVED, policy.assess(request).status());
    }

    @Test
    void refusesInvalidOrImplicitPolicyInputs() {
        assertThrows(IllegalArgumentException.class, () -> new MarginPolicy(
                " ", new BigDecimal("0.5"), new BigDecimal("0.3")));
        assertThrows(IllegalArgumentException.class, () -> new MarginPolicy(
                "v1", new BigDecimal("-0.2"), new BigDecimal("0.3")));
        MarginPolicy highRequirement = new MarginPolicy(
                "low-price-stock-v1", new BigDecimal("1.5"), new BigDecimal("1.2"));
        assertEquals(new BigDecimal("1.5"), highRequirement.initialMarginRate());
        assertThrows(IllegalArgumentException.class, () -> request(
                new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("1"),
                new ShortEligibilitySnapshot(UUID.randomUUID(), true, true, "locate", "v", OBSERVED_AT)));
    }

    private ShortRiskAssessmentRequest request(BigDecimal quantity, BigDecimal price, BigDecimal buyingPower,
            BigDecimal riskCapacity, ShortEligibilitySnapshot eligibility) {
        return new ShortRiskAssessmentRequest(UUID.randomUUID(), UUID.randomUUID(), INSTRUMENT_ID, true,
                quantity, price, buyingPower, riskCapacity, eligibility, marginPolicy(), OBSERVED_AT);
    }

    private static ShortEligibilitySnapshot eligible() {
        return new ShortEligibilitySnapshot(INSTRUMENT_ID, true, true, "locate-42",
                "borrow-feed-2026-08-02T14:00Z", OBSERVED_AT);
    }

    private static MarginPolicy marginPolicy() {
        return new MarginPolicy("margin-us-equity-v1", new BigDecimal("0.50"), new BigDecimal("0.30"));
    }
}
