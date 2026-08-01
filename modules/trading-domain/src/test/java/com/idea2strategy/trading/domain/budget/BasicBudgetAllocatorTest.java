package com.idea2strategy.trading.domain.budget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BasicBudgetAllocatorTest {

    @Test
    void equalAllocationIncludesCommittedUsageAndExpectedCosts() {
        BasicBudgetAllocationRequest request = new BasicBudgetAllocationRequest(
                new BigDecimal("10000"),
                new BigDecimal("8000"),
                new BigDecimal("500"),
                new ExpectedCostPolicy(
                        "virtual-fill-cost-v1", new BigDecimal("0.002"), new BigDecimal("0.0005")),
                List.of(new BasicStrategyBudgetRequest(
                        UUID.fromString("20000000-0000-0000-0000-000000000002"),
                        new BigDecimal("0.50"),
                        new BigDecimal("1000"),
                        new BigDecimal("500"),
                        true,
                        BasicSizingPolicy.fixedAmount(new BigDecimal("5000")),
                        List.of(
                                UUID.fromString("40000000-0000-0000-0000-000000000004"),
                                UUID.fromString("30000000-0000-0000-0000-000000000003")))));

        BasicBudgetAllocationResult result = new BasicBudgetAllocator().allocate(request);

        assertEquals(2, result.decisions().size());
        assertEquals(UUID.fromString("30000000-0000-0000-0000-000000000003"),
                result.decisions().get(0).candidateId());
        assertEquals(BudgetDecisionStatus.REDUCED, result.decisions().get(0).status());
        assertEquals(BudgetDecisionStatus.REDUCED, result.decisions().get(1).status());
        assertTrue(result.decisions().stream()
                .allMatch(decision -> decision.reasonCodes().contains(BudgetReasonCode.STRATEGY_BUDGET_CAP)));
        assertEquals(result.decisions().get(0).totalRequiredCash(), result.decisions().get(1).totalRequiredCash());
        assertTrue(result.decisions().stream()
                .map(BasicBudgetDecision::totalRequiredCash)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .compareTo(new BigDecimal("3500")) <= 0);
        assertTrue(result.decisions().stream()
                .allMatch(decision -> decision.costPolicyVersion().equals("virtual-fill-cost-v1")));

        BigDecimal expectedPrincipal = new BigDecimal("1750")
                .divide(new BigDecimal("1.0005").multiply(new BigDecimal("1.002")), 18, RoundingMode.DOWN);
        assertEquals(expectedPrincipal, result.decisions().get(0).approvedPrincipal());
        assertEquals(expectedPrincipal, result.decisions().get(1).approvedPrincipal());
    }

    @Test
    void availableBudgetRatioAllocatesOneQuarterOfRemainingStrategyBudget() {
        BasicBudgetAllocationResult result = new BasicBudgetAllocator().allocate(new BasicBudgetAllocationRequest(
                new BigDecimal("10000"), new BigDecimal("8000"), BigDecimal.ZERO, zeroCostPolicy(),
                List.of(strategy(
                        "20000000-0000-0000-0000-000000000002",
                        new BigDecimal("0.50"),
                        new BigDecimal("1000"),
                        new BigDecimal("500"),
                        true,
                        BasicSizingPolicy.availableBudgetRatio(new BigDecimal("0.25")),
                        "30000000-0000-0000-0000-000000000003"))));

        BasicBudgetDecision decision = result.decisions().getFirst();
        assertEquals(BudgetDecisionStatus.ACCEPTED, decision.status());
        assertEquals(0, decision.totalRequiredCash().compareTo(new BigDecimal("875")));
        assertEquals(0, decision.approvedPrincipal().compareTo(new BigDecimal("875")));
    }

    @Test
    void positionAboveItsCapIsRejectedWithoutForcingASell() {
        BasicBudgetAllocationResult result = new BasicBudgetAllocator().allocate(new BasicBudgetAllocationRequest(
                new BigDecimal("10000"), new BigDecimal("8000"), BigDecimal.ZERO, zeroCostPolicy(),
                List.of(strategy(
                        "20000000-0000-0000-0000-000000000002",
                        new BigDecimal("0.50"),
                        new BigDecimal("5500"),
                        BigDecimal.ZERO,
                        true,
                        BasicSizingPolicy.fixedAmount(new BigDecimal("1000")),
                        "30000000-0000-0000-0000-000000000003"))));

        BasicBudgetDecision decision = result.decisions().getFirst();
        assertEquals(BudgetDecisionStatus.REJECTED, decision.status());
        assertTrue(decision.reasonCodes().contains(BudgetReasonCode.NO_AVAILABLE_STRATEGY_BUDGET));
        assertEquals(0, decision.approvedPrincipal().signum());
        assertEquals(0, decision.totalRequiredCash().signum());
    }

    @Test
    void incompleteValuationRejectsOnlyTheAffectedStrategy() {
        BasicBudgetAllocationResult result = new BasicBudgetAllocator().allocate(new BasicBudgetAllocationRequest(
                new BigDecimal("10000"), new BigDecimal("8000"), BigDecimal.ZERO, zeroCostPolicy(),
                List.of(
                        strategy(
                                "20000000-0000-0000-0000-000000000002",
                                new BigDecimal("0.50"),
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                false,
                                BasicSizingPolicy.fixedAmount(new BigDecimal("1000")),
                                "30000000-0000-0000-0000-000000000003"),
                        strategy(
                                "40000000-0000-0000-0000-000000000004",
                                new BigDecimal("0.50"),
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                true,
                                BasicSizingPolicy.fixedAmount(new BigDecimal("1000")),
                                "50000000-0000-0000-0000-000000000005"))));

        BasicBudgetDecision rejectedDecision = result.decisions().get(0);
        BasicBudgetDecision acceptedDecision = result.decisions().get(1);
        assertEquals(BudgetDecisionStatus.REJECTED, rejectedDecision.status());
        assertTrue(rejectedDecision.reasonCodes().contains(BudgetReasonCode.POSITION_VALUATION_UNAVAILABLE));
        assertEquals(0, rejectedDecision.totalRequiredCash().signum());
        assertEquals(BudgetDecisionStatus.ACCEPTED, acceptedDecision.status());
        assertEquals(0, acceptedDecision.totalRequiredCash().compareTo(new BigDecimal("1000")));
    }

    @Test
    void strategyWithoutCandidatesEmitsNoDecisionsAndConsumesNoCash() {
        BasicBudgetAllocationResult result = new BasicBudgetAllocator().allocate(new BasicBudgetAllocationRequest(
                new BigDecimal("10000"), new BigDecimal("8000"), new BigDecimal("500"), zeroCostPolicy(),
                List.of(strategy(
                        "20000000-0000-0000-0000-000000000002",
                        new BigDecimal("0.50"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        true,
                        BasicSizingPolicy.fixedAmount(new BigDecimal("1000"))))));

        assertTrue(result.decisions().isEmpty());
        assertEquals(0, result.spendableCash().compareTo(new BigDecimal("7500")));
    }

    @Test
    void proportionallyReducesAllStrategiesWhenSharedCashIsShort() {
        BasicBudgetAllocationResult result = new BasicBudgetAllocator().allocate(new BasicBudgetAllocationRequest(
                new BigDecimal("10000"), new BigDecimal("2000"), BigDecimal.ZERO, zeroCostPolicy(),
                List.of(
                        strategy(
                                "20000000-0000-0000-0000-000000000002",
                                BigDecimal.ONE,
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                true,
                                BasicSizingPolicy.fixedAmount(new BigDecimal("3000")),
                                "30000000-0000-0000-0000-000000000003"),
                        strategy(
                                "40000000-0000-0000-0000-000000000004",
                                BigDecimal.ONE,
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                true,
                                BasicSizingPolicy.fixedAmount(new BigDecimal("1000")),
                                "50000000-0000-0000-0000-000000000005"))));

        assertTrue(result.decisions().stream()
                .map(BasicBudgetDecision::totalRequiredCash)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .compareTo(new BigDecimal("2000")) <= 0);
        assertEquals(new BigDecimal("3.000000000000000000"), result.decisions().get(0).approvedPrincipal()
                .divide(result.decisions().get(1).approvedPrincipal(), 18, RoundingMode.DOWN));
        assertTrue(result.decisions().stream()
                .filter(decision -> decision.approvedPrincipal().signum() > 0)
                .allMatch(decision -> decision.status() == BudgetDecisionStatus.REDUCED));
        assertTrue(result.decisions().stream().allMatch(decision -> decision.reasonCodes()
                .contains(BudgetReasonCode.COMMON_FUNDS_PROPORTIONAL_REDUCTION)));
    }

    @Test
    void doesNotLetStrategiesWithoutCandidatesReduceAllocatableSharedFunds() {
        BasicBudgetAllocationResult result = new BasicBudgetAllocator().allocate(new BasicBudgetAllocationRequest(
                new BigDecimal("10000"), new BigDecimal("2000"), BigDecimal.ZERO, zeroCostPolicy(),
                List.of(
                        strategy(
                                "20000000-0000-0000-0000-000000000002",
                                BigDecimal.ONE,
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                true,
                                BasicSizingPolicy.fixedAmount(new BigDecimal("3000"))),
                        strategy(
                                "40000000-0000-0000-0000-000000000004",
                                BigDecimal.ONE,
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                true,
                                BasicSizingPolicy.fixedAmount(new BigDecimal("1000")),
                                "50000000-0000-0000-0000-000000000005"))));

        BasicBudgetDecision decision = result.decisions().getFirst();
        assertEquals(0, decision.totalRequiredCash().compareTo(new BigDecimal("1000")));
        assertEquals(BudgetDecisionStatus.ACCEPTED, decision.status());
        assertTrue(decision.reasonCodes().isEmpty());
    }

    @Test
    void emitsTheSameResultWhenStrategyAndCandidateInputOrdersAreReversed() {
        BasicBudgetAllocationRequest orderedRequest = new BasicBudgetAllocationRequest(
                new BigDecimal("10000"), new BigDecimal("2000"), BigDecimal.ZERO, zeroCostPolicy(),
                List.of(
                        strategy(
                                "20000000-0000-0000-0000-000000000002",
                                BigDecimal.ONE,
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                true,
                                BasicSizingPolicy.fixedAmount(new BigDecimal("3000")),
                                "30000000-0000-0000-0000-000000000003",
                                "40000000-0000-0000-0000-000000000004"),
                        strategy(
                                "50000000-0000-0000-0000-000000000005",
                                BigDecimal.ONE,
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                true,
                                BasicSizingPolicy.fixedAmount(new BigDecimal("1000")),
                                "60000000-0000-0000-0000-000000000006",
                                "70000000-0000-0000-0000-000000000007")));
        BasicBudgetAllocationRequest reversedRequest = new BasicBudgetAllocationRequest(
                new BigDecimal("10000"), new BigDecimal("2000"), BigDecimal.ZERO, zeroCostPolicy(),
                List.of(
                        strategy(
                                "50000000-0000-0000-0000-000000000005",
                                BigDecimal.ONE,
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                true,
                                BasicSizingPolicy.fixedAmount(new BigDecimal("1000")),
                                "70000000-0000-0000-0000-000000000007",
                                "60000000-0000-0000-0000-000000000006"),
                        strategy(
                                "20000000-0000-0000-0000-000000000002",
                                BigDecimal.ONE,
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                true,
                                BasicSizingPolicy.fixedAmount(new BigDecimal("3000")),
                                "40000000-0000-0000-0000-000000000004",
                                "30000000-0000-0000-0000-000000000003")));

        BasicBudgetAllocator allocator = new BasicBudgetAllocator();

        assertEquals(allocator.allocate(orderedRequest), allocator.allocate(reversedRequest));
    }

    @Test
    void rejectsOtherwiseAllocatableStrategiesWhenNoSharedCashIsAvailable() {
        BasicBudgetAllocationResult result = new BasicBudgetAllocator().allocate(new BasicBudgetAllocationRequest(
                new BigDecimal("10000"), BigDecimal.ZERO, BigDecimal.ZERO, zeroCostPolicy(),
                List.of(strategy(
                        "20000000-0000-0000-0000-000000000002",
                        BigDecimal.ONE,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        true,
                        BasicSizingPolicy.fixedAmount(new BigDecimal("1000")),
                        "30000000-0000-0000-0000-000000000003"))));

        BasicBudgetDecision decision = result.decisions().getFirst();
        assertEquals(BudgetDecisionStatus.REJECTED, decision.status());
        assertEquals(0, decision.totalRequiredCash().signum());
        assertTrue(decision.reasonCodes().contains(BudgetReasonCode.NO_AVAILABLE_SHARED_FUNDS));
        assertTrue(decision.reasonCodes().contains(BudgetReasonCode.COMMON_FUNDS_PROPORTIONAL_REDUCTION));
    }

    @Test
    void aggregateRequiredCashStaysWithinSharedCashAndEachStrategyCap() {
        BasicBudgetAllocationResult result = new BasicBudgetAllocator().allocate(new BasicBudgetAllocationRequest(
                new BigDecimal("10000"), new BigDecimal("3000"), new BigDecimal("500"),
                new ExpectedCostPolicy("virtual-fill-cost-v1", new BigDecimal("0.002"), new BigDecimal("0.0005")),
                List.of(
                        strategy(
                                "20000000-0000-0000-0000-000000000002",
                                new BigDecimal("0.50"),
                                new BigDecimal("1000"),
                                new BigDecimal("200"),
                                true,
                                BasicSizingPolicy.fixedAmount(new BigDecimal("5000")),
                                "30000000-0000-0000-0000-000000000003",
                                "40000000-0000-0000-0000-000000000004"),
                        strategy(
                                "50000000-0000-0000-0000-000000000005",
                                new BigDecimal("0.20"),
                                new BigDecimal("800"),
                                new BigDecimal("200"),
                                true,
                                BasicSizingPolicy.fixedAmount(new BigDecimal("1200")),
                                "60000000-0000-0000-0000-000000000006"))));

        assertTrue(result.decisions().stream()
                .map(BasicBudgetDecision::totalRequiredCash)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .compareTo(result.spendableCash()) <= 0);
        assertTrue(totalRequiredCashFor(result, "20000000-0000-0000-0000-000000000002")
                .add(new BigDecimal("1200"))
                .compareTo(new BigDecimal("5000")) <= 0);
        assertTrue(totalRequiredCashFor(result, "50000000-0000-0000-0000-000000000005")
                .add(new BigDecimal("1000"))
                .compareTo(new BigDecimal("2000")) <= 0);
    }

    @Test
    void zeroStrategyCapacityProducesExplicitRejectedDecision() {
        BasicBudgetAllocationResult result = new BasicBudgetAllocator().allocate(new BasicBudgetAllocationRequest(
                new BigDecimal("10000"), new BigDecimal("1000"), BigDecimal.ZERO, zeroCostPolicy(),
                List.of(strategy(
                        "20000000-0000-0000-0000-000000000002",
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        true,
                        BasicSizingPolicy.fixedAmount(new BigDecimal("1000")),
                        "30000000-0000-0000-0000-000000000003"))));

        BasicBudgetDecision decision = result.decisions().getFirst();
        assertEquals(BudgetDecisionStatus.REJECTED, decision.status());
        assertEquals(0, decision.totalRequiredCash().signum());
        assertTrue(decision.reasonCodes().contains(BudgetReasonCode.NO_AVAILABLE_STRATEGY_BUDGET));
    }

    private static BigDecimal totalRequiredCashFor(BasicBudgetAllocationResult result, String strategyId) {
        UUID id = UUID.fromString(strategyId);
        return result.decisions().stream()
                .filter(decision -> decision.strategyId().equals(id))
                .map(BasicBudgetDecision::totalRequiredCash)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BasicStrategyBudgetRequest strategy(
            String strategyId,
            BigDecimal maximumEquityRatio,
            BigDecimal currentPositionMarketValue,
            BigDecimal reservedCash,
            boolean positionValuationComplete,
            BasicSizingPolicy sizingPolicy,
            String... candidateIds) {
        return new BasicStrategyBudgetRequest(
                UUID.fromString(strategyId),
                maximumEquityRatio,
                currentPositionMarketValue,
                reservedCash,
                positionValuationComplete,
                sizingPolicy,
                java.util.Arrays.stream(candidateIds).map(UUID::fromString).toList());
    }

    private static ExpectedCostPolicy zeroCostPolicy() {
        return new ExpectedCostPolicy("virtual-fill-cost-v1", BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
