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
        BigDecimal totalCapLimited = strategyPlans.stream()
                .filter(StrategyPlan::isAllocatable)
                .map(StrategyPlan::capLimitedTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sharedFactor = totalCapLimited.signum() == 0 || totalCapLimited.compareTo(spendableCash) <= 0
                ? BigDecimal.ONE
                : spendableCash.divide(totalCapLimited, DIVISION_SCALE, RoundingMode.DOWN);
        List<BasicBudgetDecision> decisions = new ArrayList<>();
        for (StrategyPlan strategyPlan : strategyPlans) {
            decisions.addAll(decisionsFor(applySharedFunds(strategyPlan, sharedFactor, spendableCash), request.costPolicy()));
        }

        return new BasicBudgetAllocationResult(spendableCash, decisions);
    }

    private StrategyPlan planFor(BasicStrategyBudgetRequest strategy, BigDecimal totalEquity) {
        List<UUID> candidateIds = strategy.candidateIds().stream().sorted().toList();
        if (!strategy.positionValuationComplete()) {
            return new StrategyPlan(strategy.strategyId(), candidateIds, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO,
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
                    capLimitedTotal,
                    BudgetDecisionStatus.REJECTED, List.of(BudgetReasonCode.NO_AVAILABLE_STRATEGY_BUDGET));
        }
        if (rawRequestedTotal.compareTo(remainingBudget) > 0) {
            return new StrategyPlan(strategy.strategyId(), candidateIds, rawRequestedTotal, capLimitedTotal,
                    capLimitedTotal,
                    BudgetDecisionStatus.REDUCED, List.of(BudgetReasonCode.STRATEGY_BUDGET_CAP));
        }
        return new StrategyPlan(strategy.strategyId(), candidateIds, rawRequestedTotal, capLimitedTotal,
                capLimitedTotal,
                BudgetDecisionStatus.ACCEPTED, List.of());
    }

    private StrategyPlan applySharedFunds(StrategyPlan strategyPlan, BigDecimal sharedFactor, BigDecimal spendableCash) {
        if (strategyPlan.capLimitedTotal().signum() == 0 || sharedFactor.compareTo(BigDecimal.ONE) >= 0) {
            return strategyPlan;
        }

        if (spendableCash.signum() == 0) {
            return new StrategyPlan(
                    strategyPlan.strategyId(),
                    strategyPlan.candidateIds(),
                    strategyPlan.rawRequestedTotal(),
                    strategyPlan.capLimitedTotal(),
                    BigDecimal.ZERO,
                    BudgetDecisionStatus.REJECTED,
                    withReason(
                            withReason(strategyPlan.reasonCodes(), BudgetReasonCode.COMMON_FUNDS_PROPORTIONAL_REDUCTION),
                            BudgetReasonCode.NO_AVAILABLE_SHARED_FUNDS));
        }

        return new StrategyPlan(
                strategyPlan.strategyId(),
                strategyPlan.candidateIds(),
                strategyPlan.rawRequestedTotal(),
                strategyPlan.capLimitedTotal(),
                strategyPlan.capLimitedTotal().multiply(sharedFactor),
                BudgetDecisionStatus.REDUCED,
                withReason(strategyPlan.reasonCodes(), BudgetReasonCode.COMMON_FUNDS_PROPORTIONAL_REDUCTION));
    }

    private List<BudgetReasonCode> withReason(List<BudgetReasonCode> reasonCodes, BudgetReasonCode additionalReason) {
        List<BudgetReasonCode> reasons = new ArrayList<>(reasonCodes);
        if (!reasons.contains(additionalReason)) {
            reasons.add(additionalReason);
        }
        return List.copyOf(reasons);
    }

    private List<BasicBudgetDecision> decisionsFor(StrategyPlan strategyPlan, ExpectedCostPolicy costPolicy) {
        int candidateCount = strategyPlan.candidateIds().size();
        if (candidateCount == 0) {
            return List.of();
        }

        BigDecimal candidateRequestedCash = strategyPlan.rawRequestedTotal()
                .divide(BigDecimal.valueOf(candidateCount), DIVISION_SCALE, RoundingMode.DOWN);
        BigDecimal candidateEnvelope = strategyPlan.approvedStrategyEnvelope()
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
            BigDecimal approvedStrategyEnvelope,
            BudgetDecisionStatus status,
            List<BudgetReasonCode> reasonCodes) {

        private boolean isAllocatable() {
            return !candidateIds.isEmpty()
                    && capLimitedTotal.signum() > 0
                    && status != BudgetDecisionStatus.REJECTED;
        }
    }
}
