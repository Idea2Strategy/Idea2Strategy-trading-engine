package com.idea2strategy.trading.domain.budget;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BasicBudgetModelTest {

    @Test
    void rejectsAValueRatioAboveOne() {
        assertThrows(IllegalArgumentException.class,
                () -> BasicSizingPolicy.availableBudgetRatio(new BigDecimal("1.01")));
    }

    @Test
    void rejectsCostPolicyRatesOutsideZeroToOne() {
        assertThrows(IllegalArgumentException.class,
                () -> new ExpectedCostPolicy("virtual-fill-cost-v1", new BigDecimal("-0.01"), BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new ExpectedCostPolicy("virtual-fill-cost-v1", BigDecimal.ZERO, new BigDecimal("-0.01")));
        assertThrows(IllegalArgumentException.class,
                () -> new ExpectedCostPolicy("virtual-fill-cost-v1", new BigDecimal("1.01"), BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new ExpectedCostPolicy("virtual-fill-cost-v1", BigDecimal.ZERO, new BigDecimal("1.01")));
    }

    @Test
    void rejectsBlankCostPolicyVersion() {
        assertThrows(IllegalArgumentException.class,
                () -> new ExpectedCostPolicy(" ", BigDecimal.ZERO, BigDecimal.ZERO));
    }

    @Test
    void rejectsNegativeCashPositionValueAndReservation() {
        assertThrows(IllegalArgumentException.class, () -> new BasicBudgetAllocationRequest(
                new BigDecimal("10000"), new BigDecimal("-1"), BigDecimal.ZERO,
                new ExpectedCostPolicy("virtual-fill-cost-v1", BigDecimal.ZERO, BigDecimal.ZERO), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new BasicBudgetAllocationRequest(
                new BigDecimal("10000"), BigDecimal.ZERO, new BigDecimal("-1"),
                new ExpectedCostPolicy("virtual-fill-cost-v1", BigDecimal.ZERO, BigDecimal.ZERO), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new BasicStrategyBudgetRequest(
                UUID.fromString("20000000-0000-0000-0000-000000000002"), new BigDecimal("0.50"),
                new BigDecimal("-1"), BigDecimal.ZERO, true,
                BasicSizingPolicy.fixedAmount(new BigDecimal("1000")), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new BasicStrategyBudgetRequest(
                UUID.fromString("20000000-0000-0000-0000-000000000002"), new BigDecimal("0.50"),
                BigDecimal.ZERO, new BigDecimal("-1"), true,
                BasicSizingPolicy.fixedAmount(new BigDecimal("1000")), List.of()));
    }

    @Test
    void rejectsDuplicateCandidateIdsWithinAStrategy() {
        UUID duplicate = UUID.fromString("10000000-0000-0000-0000-000000000001");
        assertThrows(IllegalArgumentException.class, () -> new BasicStrategyBudgetRequest(
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                new BigDecimal("0.50"), BigDecimal.ZERO, BigDecimal.ZERO, true,
                BasicSizingPolicy.fixedAmount(new BigDecimal("1000")),
                List.of(duplicate, duplicate)));
    }

    @Test
    void rejectsDuplicateStrategyIdsWithinARequest() {
        BasicStrategyBudgetRequest strategy = validStrategy();
        assertThrows(IllegalArgumentException.class, () -> new BasicBudgetAllocationRequest(
                new BigDecimal("10000"), new BigDecimal("8000"), BigDecimal.ZERO,
                new ExpectedCostPolicy("virtual-fill-cost-v1", new BigDecimal("0.002"), new BigDecimal("0.0005")),
                List.of(strategy, strategy)));
    }

    @Test
    void inputRecordsRejectNullFieldsWithIllegalArgumentException() {
        BasicSizingPolicy sizingPolicy = BasicSizingPolicy.fixedAmount(new BigDecimal("1000"));
        ExpectedCostPolicy costPolicy = new ExpectedCostPolicy(
                "virtual-fill-cost-v1", new BigDecimal("0.002"), new BigDecimal("0.0005"));
        BasicStrategyBudgetRequest strategy = validStrategy();
        UUID candidateId = UUID.fromString("40000000-0000-0000-0000-000000000004");

        assertThrows(IllegalArgumentException.class, () -> new BasicSizingPolicy(null, BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new BasicSizingPolicy(BasicSizingMode.FIXED_AMOUNT, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ExpectedCostPolicy(null, BigDecimal.ZERO, BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new ExpectedCostPolicy("virtual-fill-cost-v1", null, BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new ExpectedCostPolicy("virtual-fill-cost-v1", BigDecimal.ZERO, null));
        assertThrows(IllegalArgumentException.class, () -> new BasicStrategyBudgetRequest(
                null, new BigDecimal("0.50"), BigDecimal.ZERO, BigDecimal.ZERO, true,
                sizingPolicy, List.of(candidateId)));
        assertThrows(IllegalArgumentException.class, () -> new BasicStrategyBudgetRequest(
                strategy.strategyId(), null, BigDecimal.ZERO, BigDecimal.ZERO, true,
                sizingPolicy, List.of(candidateId)));
        assertThrows(IllegalArgumentException.class, () -> new BasicStrategyBudgetRequest(
                strategy.strategyId(), new BigDecimal("0.50"), null, BigDecimal.ZERO, true,
                sizingPolicy, List.of(candidateId)));
        assertThrows(IllegalArgumentException.class, () -> new BasicStrategyBudgetRequest(
                strategy.strategyId(), new BigDecimal("0.50"), BigDecimal.ZERO, null, true,
                sizingPolicy, List.of(candidateId)));
        assertThrows(IllegalArgumentException.class, () -> new BasicStrategyBudgetRequest(
                strategy.strategyId(), new BigDecimal("0.50"), BigDecimal.ZERO, BigDecimal.ZERO, true,
                null, List.of(candidateId)));
        assertThrows(IllegalArgumentException.class, () -> new BasicBudgetAllocationRequest(
                null, new BigDecimal("8000"), BigDecimal.ZERO, costPolicy, List.of(strategy)));
        assertThrows(IllegalArgumentException.class, () -> new BasicBudgetAllocationRequest(
                new BigDecimal("10000"), null, BigDecimal.ZERO, costPolicy, List.of(strategy)));
        assertThrows(IllegalArgumentException.class, () -> new BasicBudgetAllocationRequest(
                new BigDecimal("10000"), new BigDecimal("8000"), null, costPolicy, List.of(strategy)));
        assertThrows(IllegalArgumentException.class, () -> new BasicBudgetAllocationRequest(
                new BigDecimal("10000"), new BigDecimal("8000"), BigDecimal.ZERO, null, List.of(strategy)));
    }

    @Test
    void inputRecordsRejectNullListsAndListElementsWithIllegalArgumentException() {
        BasicStrategyBudgetRequest strategy = validStrategy();
        ExpectedCostPolicy costPolicy = new ExpectedCostPolicy(
                "virtual-fill-cost-v1", new BigDecimal("0.002"), new BigDecimal("0.0005"));

        assertThrows(IllegalArgumentException.class, () -> new BasicStrategyBudgetRequest(
                strategy.strategyId(), new BigDecimal("0.50"), BigDecimal.ZERO, BigDecimal.ZERO, true,
                BasicSizingPolicy.fixedAmount(new BigDecimal("1000")), null));
        assertThrows(IllegalArgumentException.class, () -> new BasicStrategyBudgetRequest(
                strategy.strategyId(), new BigDecimal("0.50"), BigDecimal.ZERO, BigDecimal.ZERO, true,
                BasicSizingPolicy.fixedAmount(new BigDecimal("1000")), Collections.singletonList(null)));
        assertThrows(IllegalArgumentException.class, () -> new BasicBudgetAllocationRequest(
                new BigDecimal("10000"), new BigDecimal("8000"), BigDecimal.ZERO, costPolicy, null));
        assertThrows(IllegalArgumentException.class, () -> new BasicBudgetAllocationRequest(
                new BigDecimal("10000"), new BigDecimal("8000"), BigDecimal.ZERO, costPolicy,
                Collections.singletonList(null)));
    }

    @Test
    void rejectsAllocationRequestsWithoutStrategiesButAllowsStrategiesWithoutCandidates() {
        ExpectedCostPolicy costPolicy = new ExpectedCostPolicy(
                "virtual-fill-cost-v1", new BigDecimal("0.002"), new BigDecimal("0.0005"));

        assertThrows(IllegalArgumentException.class, () -> new BasicBudgetAllocationRequest(
                new BigDecimal("10000"), new BigDecimal("8000"), BigDecimal.ZERO, costPolicy, List.of()));
        assertDoesNotThrow(() -> new BasicStrategyBudgetRequest(
                UUID.fromString("30000000-0000-0000-0000-000000000003"),
                new BigDecimal("0.50"), BigDecimal.ZERO, BigDecimal.ZERO, true,
                BasicSizingPolicy.fixedAmount(new BigDecimal("1000")), List.of()));
    }

    @Test
    void rejectsDecisionWhoseTotalRequiredCashDoesNotMatchItsComponents() {
        assertThrows(IllegalArgumentException.class, () -> decision(
                new BigDecimal("100"), new BigDecimal("0.05"), new BigDecimal("0.20"),
                new BigDecimal("100.24"), BudgetDecisionStatus.ACCEPTED));
    }

    @Test
    void permitsARejectedDecisionWithZeroApprovedCashAndCosts() {
        assertDoesNotThrow(() -> decision(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BudgetDecisionStatus.REJECTED));
    }

    private static BasicBudgetDecision decision(
            BigDecimal approvedPrincipal,
            BigDecimal expectedSlippage,
            BigDecimal expectedFee,
            BigDecimal totalRequiredCash,
            BudgetDecisionStatus status) {
        return new BasicBudgetDecision(
                UUID.fromString("30000000-0000-0000-0000-000000000003"),
                UUID.fromString("40000000-0000-0000-0000-000000000004"),
                new BigDecimal("1000"),
                approvedPrincipal,
                expectedSlippage,
                expectedFee,
                totalRequiredCash,
                status,
                status == BudgetDecisionStatus.REJECTED
                        ? List.of(BudgetReasonCode.NO_AVAILABLE_SHARED_FUNDS)
                        : List.of(),
                "virtual-fill-cost-v1");
    }

    private static BasicStrategyBudgetRequest validStrategy() {
        return new BasicStrategyBudgetRequest(
                UUID.fromString("30000000-0000-0000-0000-000000000003"),
                new BigDecimal("0.50"), BigDecimal.ZERO, BigDecimal.ZERO, true,
                BasicSizingPolicy.fixedAmount(new BigDecimal("1000")),
                List.of(UUID.fromString("40000000-0000-0000-0000-000000000004")));
    }
}
