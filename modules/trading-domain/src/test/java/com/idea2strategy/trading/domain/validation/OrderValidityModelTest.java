package com.idea2strategy.trading.domain.validation;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderValidityModelTest {

    @Test
    void rejectsDuplicateRiskMetricCodes() {
        RiskMetricEvaluation gross = metric("gross-exposure", "risk-v1", "90", "95", "100");

        assertThrows(IllegalArgumentException.class, () -> request(
                RiskDirection.INCREASING, true, List.of(gross, gross), validFunds(), validPolicy()));
    }

    @Test
    void rejectsNegativeMoneyAndBlankVersions() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new AvailableFundsSnapshot(" ", BigDecimal.TEN)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new AvailableFundsSnapshot("funds-v1", new BigDecimal("-0.01"))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new InstrumentNumericPolicy("instrument-v1", BigDecimal.ONE,
                                BigDecimal.ONE, -1, 2)));
    }

    @Test
    void copiesInputCollections() {
        ArrayList<RiskMetricEvaluation> metrics = new ArrayList<>();
        metrics.add(metric("gross-exposure", "risk-v1", "90", "95", "100"));
        OrderValidityRequest request = request(
                RiskDirection.INCREASING, true, metrics, validFunds(), validPolicy());

        metrics.clear();

        assertEquals(1, request.riskEvaluations().size());
    }

    @Test
    void rejectsMissingOrInvalidRequestValues() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new OrderValidityRequest(
                        null, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, "funds-v1", validFunds(),
                        RiskDirection.INCREASING, true, List.of(), validPolicy())),
                () -> assertThrows(IllegalArgumentException.class, () -> new OrderValidityRequest(
                        proposalId(), new BigDecimal("-0.01"), BigDecimal.ONE, BigDecimal.ONE, "funds-v1",
                        validFunds(), RiskDirection.INCREASING, true, List.of(), validPolicy())),
                () -> assertThrows(IllegalArgumentException.class, () -> new OrderValidityRequest(
                        proposalId(), BigDecimal.ONE, new BigDecimal("-0.01"), BigDecimal.ONE, "funds-v1",
                        validFunds(), RiskDirection.INCREASING, true, List.of(), validPolicy())),
                () -> assertThrows(IllegalArgumentException.class, () -> new OrderValidityRequest(
                        proposalId(), BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("-0.01"), "funds-v1",
                        validFunds(), RiskDirection.INCREASING, true, List.of(), validPolicy())),
                () -> assertThrows(IllegalArgumentException.class, () -> new OrderValidityRequest(
                        proposalId(), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, " ", validFunds(),
                        RiskDirection.INCREASING, true, List.of(), validPolicy())),
                () -> assertThrows(IllegalArgumentException.class, () -> new OrderValidityRequest(
                        proposalId(), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, "funds-v1", validFunds(),
                        null, true, List.of(), validPolicy())),
                () -> assertThrows(IllegalArgumentException.class, () -> new OrderValidityRequest(
                        proposalId(), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, "funds-v1", validFunds(),
                        RiskDirection.INCREASING, true, null, validPolicy())));
    }

    @Test
    void permitsUnavailableFundsAndInstrumentPolicyAsBusinessStates() {
        OrderValidityRequest request = request(
                RiskDirection.REDUCING, false, List.of(), null, null);

        assertAll(
                () -> assertEquals(null, request.latestFundsSnapshot()),
                () -> assertEquals(null, request.instrumentPolicy()));
    }

    @Test
    void rejectsInvalidRiskMetricAndInstrumentPolicyValues() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new RiskMetricEvaluation(" ", "risk-v1", BigDecimal.ZERO, BigDecimal.ZERO,
                                BigDecimal.ZERO)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new RiskMetricEvaluation("gross-exposure", " ", BigDecimal.ZERO, BigDecimal.ZERO,
                                BigDecimal.ZERO)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new RiskMetricEvaluation("gross-exposure", "risk-v1", new BigDecimal("-0.01"),
                                BigDecimal.ZERO, BigDecimal.ZERO)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new RiskMetricEvaluation("gross-exposure", "risk-v1", BigDecimal.ZERO,
                                new BigDecimal("-0.01"), BigDecimal.ZERO)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new RiskMetricEvaluation("gross-exposure", "risk-v1", BigDecimal.ZERO,
                                BigDecimal.ZERO, new BigDecimal("-0.01"))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new InstrumentNumericPolicy(" ", BigDecimal.ONE, BigDecimal.ONE, 2, 2)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new InstrumentNumericPolicy("instrument-v1", new BigDecimal("-0.01"),
                                BigDecimal.ONE, 2, 2)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new InstrumentNumericPolicy("instrument-v1", BigDecimal.ONE,
                                new BigDecimal("-0.01"), 2, 2)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new InstrumentNumericPolicy("instrument-v1", BigDecimal.ONE, BigDecimal.ONE, 2, -1)));
    }

    @Test
    void rejectsUnsortedOrDuplicatedResultReasons() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> result(
                        OrderValidityStatus.REJECTED,
                        List.of(OrderValidityReason.RISK_LIMIT_EXCEEDED,
                                OrderValidityReason.AVAILABLE_FUNDS_UNAVAILABLE),
                        List.of())),
                () -> assertThrows(IllegalArgumentException.class, () -> result(
                        OrderValidityStatus.REJECTED,
                        List.of(OrderValidityReason.RISK_LIMIT_EXCEEDED,
                                OrderValidityReason.RISK_LIMIT_EXCEEDED),
                        List.of())));
    }

    @Test
    void enforcesResultStatusReasonRelationship() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> result(
                        OrderValidityStatus.ACCEPTED,
                        List.of(OrderValidityReason.RISK_LIMIT_EXCEEDED), List.of())),
                () -> assertThrows(IllegalArgumentException.class, () -> result(
                        OrderValidityStatus.REJECTED, List.of(), List.of())),
                () -> assertThrows(IllegalArgumentException.class, () -> result(
                        OrderValidityStatus.REEVALUATION_REQUIRED, List.of(), List.of())));
    }

    @Test
    void rejectsUnsortedOrDuplicatedRiskPolicyEvidence() {
        RiskPolicyEvidence concentration = new RiskPolicyEvidence("concentration", "risk-v1");
        RiskPolicyEvidence gross = new RiskPolicyEvidence("gross-exposure", "risk-v1");

        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> result(
                        OrderValidityStatus.ACCEPTED, List.of(), List.of(gross, concentration))),
                () -> assertThrows(IllegalArgumentException.class, () -> result(
                        OrderValidityStatus.ACCEPTED, List.of(), List.of(gross, gross))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new RiskPolicyEvidence(" ", "risk-v1")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new RiskPolicyEvidence("gross-exposure", " ")));
    }

    @Test
    void copiesResultCollections() {
        ArrayList<OrderValidityReason> reasons = new ArrayList<>(
                List.of(OrderValidityReason.RISK_LIMIT_EXCEEDED));
        ArrayList<RiskPolicyEvidence> evidence = new ArrayList<>(
                List.of(new RiskPolicyEvidence("gross-exposure", "risk-v1")));
        OrderValidityResult result = result(OrderValidityStatus.REJECTED, reasons, evidence);

        reasons.clear();
        evidence.clear();

        assertAll(
                () -> assertEquals(1, result.reasons().size()),
                () -> assertEquals(1, result.riskPolicyEvidence().size()));
    }

    private static OrderValidityRequest request(
            RiskDirection riskDirection,
            boolean riskEvaluationComplete,
            List<RiskMetricEvaluation> riskEvaluations,
            AvailableFundsSnapshot latestFundsSnapshot,
            InstrumentNumericPolicy instrumentPolicy) {
        return new OrderValidityRequest(
                proposalId(), new BigDecimal("10"), new BigDecimal("25.50"), new BigDecimal("255.00"),
                "funds-v1", latestFundsSnapshot, riskDirection, riskEvaluationComplete, riskEvaluations,
                instrumentPolicy);
    }

    private static OrderValidityResult result(
            OrderValidityStatus status,
            List<OrderValidityReason> reasons,
            List<RiskPolicyEvidence> evidence) {
        return new OrderValidityResult(
                proposalId(), status, reasons, "funds-v1", "instrument-v1", evidence);
    }

    private static AvailableFundsSnapshot validFunds() {
        return new AvailableFundsSnapshot("funds-v1", new BigDecimal("1000.00"));
    }

    private static InstrumentNumericPolicy validPolicy() {
        return new InstrumentNumericPolicy("instrument-v1", new BigDecimal("100.00"),
                new BigDecimal("1"), 4, 2);
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

    private static UUID proposalId() {
        return UUID.fromString("10000000-0000-0000-0000-000000000001");
    }
}
