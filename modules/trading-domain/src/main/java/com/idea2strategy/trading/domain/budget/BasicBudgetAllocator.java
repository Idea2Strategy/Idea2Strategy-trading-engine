package com.idea2strategy.trading.domain.budget;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class BasicBudgetAllocator {

    private static final int DIVISION_SCALE = 18;

    public BasicBudgetAllocationResult allocate(BasicBudgetAllocationRequest request) {
        Objects.requireNonNull(request, "request");

        BigDecimal spendableCash = request.grossAvailableCash()
                .subtract(request.sharedReservedCash())
                .max(BigDecimal.ZERO);
        List<StrategyPlan> strategyPlans = request.strategies().stream()
                .sorted(Comparator.comparing(BasicStrategyBudgetRequest::strategyId))
                .map(strategy -> planFor(strategy, request.totalEquity()))
                .toList();
        List<BasicBudgetDecision> decisions = new ArrayList<>();
        for (StrategyPlan strategyPlan : strategyPlans) {
            decisions.addAll(decisionsFor(strategyPlan, request.costPolicy()));
        }

        return new BasicBudgetAllocationResult(spendableCash, decisions);
    }

    private StrategyPlan planFor(BasicStrategyBudgetRequest strategy, BigDecimal totalEquity) {
        List<UUID> candidateIds = strategy.candidateIds().stream().sorted().toList();
        if (!strategy.positionValuationComplete()) {
            return new StrategyPlan(strategy.strategyId(), candidateIds, BigDecimal.ZERO, BigDecimal.ZERO,
                    BudgetDecisionStatus.REJECTED, List.of(BudgetReasonCode.POSITION_VALUATION_UNAVAILABLE));
        }

        BigDecimal strategyCap = totalEquity.multiply(strategy.maximumEquityRatio());
        BigDecimal committedUsage = strategy.currentPositionMarketValue().add(strategy.reservedCash());
        BigDecimal remainingBudget = strategyCap.subtract(committedUsage).max(BigDecimal.ZERO);
        BigDecimal rawRequestedTotal = strategy.sizingPolicy().mode() == BasicSizingMode.FIXED_AMOUNT
                ? strategy.sizingPolicy().value()
                : remainingBudget.multiply(strategy.sizingPolicy().value());
        BigDecimal capLimitedTotal = rawRequestedTotal.min(remainingBudget);
        if (remainingBudget.signum() == 0) {
            return new StrategyPlan(strategy.strategyId(), candidateIds, rawRequestedTotal, capLimitedTotal,
                    BudgetDecisionStatus.REJECTED, List.of(BudgetReasonCode.NO_AVAILABLE_STRATEGY_BUDGET));
        }
        if (rawRequestedTotal.compareTo(remainingBudget) > 0) {
            return new StrategyPlan(strategy.strategyId(), candidateIds, rawRequestedTotal, capLimitedTotal,
                    BudgetDecisionStatus.REDUCED, List.of(BudgetReasonCode.STRATEGY_BUDGET_CAP));
        }
        return new StrategyPlan(strategy.strategyId(), candidateIds, rawRequestedTotal, capLimitedTotal,
                BudgetDecisionStatus.ACCEPTED, List.of());
    }

    private List<BasicBudgetDecision> decisionsFor(StrategyPlan strategyPlan, ExpectedCostPolicy costPolicy) {
        int candidateCount = strategyPlan.candidateIds().size();
        if (candidateCount == 0) {
            return List.of();
        }

        BigDecimal candidateRequestedCash = strategyPlan.rawRequestedTotal()
                .divide(BigDecimal.valueOf(candidateCount), DIVISION_SCALE, RoundingMode.DOWN);
        BigDecimal candidateEnvelope = strategyPlan.capLimitedTotal()
                .divide(BigDecimal.valueOf(candidateCount), DIVISION_SCALE, RoundingMode.DOWN);
        BigDecimal costMultiplier = BigDecimal.ONE.add(costPolicy.adverseBuySlippageRate())
                .multiply(BigDecimal.ONE.add(costPolicy.feeRate()));
        BigDecimal approvedPrincipal = candidateEnvelope.divide(costMultiplier, DIVISION_SCALE, RoundingMode.DOWN);
        BigDecimal expectedSlippage = approvedPrincipal.multiply(costPolicy.adverseBuySlippageRate());
        BigDecimal expectedFee = approvedPrincipal.add(expectedSlippage).multiply(costPolicy.feeRate());
        BigDecimal totalRequiredCash = approvedPrincipal.add(expectedSlippage).add(expectedFee);

        return strategyPlan.candidateIds().stream()
                .map(candidateId -> new BasicBudgetDecision(
                        strategyPlan.strategyId(),
                        candidateId,
                        candidateRequestedCash,
                        approvedPrincipal,
                        expectedSlippage,
                        expectedFee,
                        totalRequiredCash,
                        strategyPlan.status(),
                        strategyPlan.reasonCodes(),
                        costPolicy.version()))
                .toList();
    }

    private record StrategyPlan(
            UUID strategyId,
            List<UUID> candidateIds,
            BigDecimal rawRequestedTotal,
            BigDecimal capLimitedTotal,
            BudgetDecisionStatus status,
            List<BudgetReasonCode> reasonCodes) {
    }
}
