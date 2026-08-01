package com.idea2strategy.trading.domain.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderValidityValidatorTest {

    private final OrderValidityValidator validator = new OrderValidityValidator();

    @Test
    void blocksRiskIncreaseButAllowsRiskReductionAboveLimit() {
        OrderValidityResult increasing = validator.validate(request(
                RiskDirection.INCREASING, true, List.of(metric("gross-exposure", "risk-v1", "120", "130", "100"))));
        OrderValidityResult reducing = validator.validate(request(
                RiskDirection.REDUCING, true, List.of(metric("gross-exposure", "risk-v1", "120", "110", "100"))));

        assertEquals(OrderValidityStatus.REJECTED, increasing.status());
        assertEquals(List.of(OrderValidityReason.RISK_LIMIT_EXCEEDED), increasing.reasons());
        assertEquals(OrderValidityStatus.ACCEPTED, reducing.status());
        assertTrue(reducing.reasons().isEmpty());
    }

    @Test
    void requiresReevaluationWhenLatestFundsAreUnavailable() {
        OrderValidityResult result = validator.validate(request(
                "funds-v1", null, RiskDirection.INCREASING, true, List.of()));

        assertEquals(OrderValidityStatus.REEVALUATION_REQUIRED, result.status());
        assertEquals(List.of(OrderValidityReason.AVAILABLE_FUNDS_UNAVAILABLE), result.reasons());
        assertEquals("funds-v1", result.fundsSnapshotVersion());
    }

    @Test
    void requiresReevaluationWhenLatestFundsSnapshotChangesWithoutChangingProposal() {
        OrderValidityRequest request = request(
                "funds-v1", new AvailableFundsSnapshot("funds-v2", new BigDecimal("1000.00")),
                RiskDirection.INCREASING, true, List.of());

        OrderValidityResult result = validator.validate(request);

        assertEquals(OrderValidityStatus.REEVALUATION_REQUIRED, result.status());
        assertEquals(List.of(OrderValidityReason.AVAILABLE_FUNDS_SNAPSHOT_CHANGED), result.reasons());
        assertEquals("funds-v2", result.fundsSnapshotVersion());
        assertEquals(new BigDecimal("10"), request.quantity());
        assertEquals(new BigDecimal("255.00"), request.totalRequiredCash());
    }

    @Test
    void requiresReevaluationWhenMatchingFundsAreInsufficient() {
        OrderValidityResult result = validator.validate(request(
                "funds-v1", new AvailableFundsSnapshot("funds-v1", new BigDecimal("254.99")),
                RiskDirection.INCREASING, true, List.of()));

        assertEquals(OrderValidityStatus.REEVALUATION_REQUIRED, result.status());
        assertEquals(List.of(OrderValidityReason.INSUFFICIENT_AVAILABLE_FUNDS), result.reasons());
    }

    @Test
    void rejectsIncompleteRiskEvaluationWhilePreservingObservedEvidence() {
        OrderValidityResult result = validator.validate(request(
                "funds-v1", validFunds(), RiskDirection.INCREASING, false,
                List.of(metric("gross-exposure", "risk-v1", "90", "95", "100"))));

        assertEquals(OrderValidityStatus.REJECTED, result.status());
        assertEquals(List.of(OrderValidityReason.RISK_EVALUATION_UNAVAILABLE), result.reasons());
        assertEquals(List.of(new RiskPolicyEvidence("gross-exposure", "risk-v1")), result.riskPolicyEvidence());
    }

    @Test
    void rejectsWhenAnyIndependentRiskMetricExceedsItsOwnMaximum() {
        OrderValidityResult result = validator.validate(request(
                "funds-v1", validFunds(), RiskDirection.INCREASING, true,
                List.of(
                        metric("gross-exposure", "risk-v1", "90", "95", "100"),
                        metric("concentration", "risk-v2", "40", "51", "50"))));

        assertEquals(OrderValidityStatus.REJECTED, result.status());
        assertEquals(List.of(OrderValidityReason.RISK_LIMIT_EXCEEDED), result.reasons());
    }

    @Test
    void rejectsReducingProposalWhenAnyMetricIncreases() {
        OrderValidityResult result = validator.validate(request(
                "funds-v1", validFunds(), RiskDirection.REDUCING, true,
                List.of(
                        metric("gross-exposure", "risk-v1", "120", "110", "100"),
                        metric("concentration", "risk-v2", "40", "41", "50"))));

        assertEquals(OrderValidityStatus.REJECTED, result.status());
        assertEquals(List.of(OrderValidityReason.RISK_REDUCTION_NOT_CONFIRMED), result.reasons());
    }

    @Test
    void returnsEqualResultWithSortedEvidenceWhenRiskEvaluationOrderChanges() {
        RiskMetricEvaluation concentration = metric("concentration", "risk-v2", "40", "45", "50");
        RiskMetricEvaluation gross = metric("gross-exposure", "risk-v1", "90", "95", "100");

        OrderValidityResult first = validator.validate(request(
                "funds-v1", validFunds(), RiskDirection.INCREASING, true, List.of(concentration, gross)));
        OrderValidityResult second = validator.validate(request(
                "funds-v1", validFunds(), RiskDirection.INCREASING, true, List.of(gross, concentration)));

        assertEquals(first, second);
        assertEquals(List.of(
                new RiskPolicyEvidence("concentration", "risk-v2"),
                new RiskPolicyEvidence("gross-exposure", "risk-v1")), first.riskPolicyEvidence());
    }

    @Test
    void givesRiskRejectionPrecedenceOverFundsReevaluationWhilePreservingBothReasons() {
        OrderValidityResult result = validator.validate(request(
                "funds-v1", null, RiskDirection.INCREASING, true,
                List.of(metric("gross-exposure", "risk-v1", "120", "130", "100"))));

        assertEquals(OrderValidityStatus.REJECTED, result.status());
        assertEquals(List.of(
                OrderValidityReason.AVAILABLE_FUNDS_UNAVAILABLE,
                OrderValidityReason.RISK_LIMIT_EXCEEDED), result.reasons());
    }

    private static OrderValidityRequest request(
            RiskDirection riskDirection,
            boolean riskEvaluationComplete,
            List<RiskMetricEvaluation> riskEvaluations) {
        return request("funds-v1", validFunds(), riskDirection, riskEvaluationComplete, riskEvaluations);
    }

    private static OrderValidityRequest request(
            String allocationFundsSnapshotVersion,
            AvailableFundsSnapshot latestFundsSnapshot,
            RiskDirection riskDirection,
            boolean riskEvaluationComplete,
            List<RiskMetricEvaluation> riskEvaluations) {
        return new OrderValidityRequest(
                UUID.fromString("10000000-0000-0000-0000-000000000001"), new BigDecimal("10"),
                new BigDecimal("25.50"), new BigDecimal("255.00"), allocationFundsSnapshotVersion,
                latestFundsSnapshot, riskDirection,
                riskEvaluationComplete, riskEvaluations,
                new InstrumentNumericPolicy("instrument-v1", new BigDecimal("100.00"), BigDecimal.ONE, 4, 2));
    }

    private static AvailableFundsSnapshot validFunds() {
        return new AvailableFundsSnapshot("funds-v1", new BigDecimal("1000.00"));
    }

    private static RiskMetricEvaluation metric(
            String metricCode,
            String policyVersion,
            String currentValue,
            String projectedValue,
            String maximumAllowedValue) {
        return new RiskMetricEvaluation(metricCode, policyVersion, new BigDecimal(currentValue),
                new BigDecimal(projectedValue), new BigDecimal(maximumAllowedValue));
    }
}
