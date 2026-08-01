package com.idea2strategy.trading.strategy.runtime.basic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class BasicStrategyExecutor {
    private static final String INPUT_MISSING = "INSTRUMENT_INPUT_MISSING";
    private static final String CONDITION_ERROR = "CONDITION_EVALUATION_ERROR";

    public BasicExecutionResult execute(BasicExecutionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        List<BasicInstrumentDecision> decisions = new ArrayList<>();
        for (BasicFlow flow : request.flows()) {
            List<BasicInstrumentDecision> flowDecisions = flow.instrumentIds().stream()
                    .sorted(Comparator.naturalOrder())
                    .map(instrumentId -> evaluateInstrument(
                            flow, instrumentId, request.instrumentInputs().get(instrumentId)))
                    .toList();
            decisions.addAll(assignEqualBuyShares(flowDecisions));
        }
        return new BasicExecutionResult(request.evaluationId(), decisions);
    }

    private BasicInstrumentDecision evaluateInstrument(
            BasicFlow flow,
            UUID instrumentId,
            BasicInstrumentInput input) {
        if (input == null) {
            return new BasicInstrumentDecision(
                    flow.flowId(), instrumentId, flow.side(), BasicDecisionStatus.INPUT_MISSING,
                    List.of(), Optional.of("$input"), Optional.of(INPUT_MISSING), Optional.empty());
        }
        List<BasicStepTrace> trace = new ArrayList<>();
        for (BasicConditionStep step : flow.conditionSteps()) {
            try {
                BasicConditionOutcome outcome = Objects.requireNonNull(
                        step.evaluate(input), "condition outcome must not be null");
                trace.add(new BasicStepTrace(step.stepId(), outcome.passed(), outcome.reasonCode(), outcome.evidence()));
                if (!outcome.passed()) {
                    return rejected(flow, input, BasicDecisionStatus.CONDITION_NOT_MET, trace,
                            step.stepId(), outcome.reasonCode());
                }
            } catch (RuntimeException failure) {
                trace.add(new BasicStepTrace(step.stepId(), false, CONDITION_ERROR,
                        Map.of("errorType", failure.getClass().getSimpleName())));
                return rejected(flow, input, BasicDecisionStatus.CONDITION_ERROR, trace,
                        step.stepId(), CONDITION_ERROR);
            }
        }
        return new BasicInstrumentDecision(
                flow.flowId(), input.instrumentId(), flow.side(), BasicDecisionStatus.CANDIDATE,
                trace, Optional.empty(), Optional.empty(), Optional.empty());
    }

    private BasicInstrumentDecision rejected(
            BasicFlow flow,
            BasicInstrumentInput input,
            BasicDecisionStatus status,
            List<BasicStepTrace> trace,
            String failureStepId,
            String failureReason) {
        return new BasicInstrumentDecision(
                flow.flowId(), input.instrumentId(), flow.side(), status, trace,
                Optional.of(failureStepId), Optional.of(failureReason), Optional.empty());
    }

    private List<BasicInstrumentDecision> assignEqualBuyShares(List<BasicInstrumentDecision> decisions) {
        if (decisions.isEmpty() || decisions.get(0).side() != BasicOrderSide.BUY) {
            return decisions;
        }
        int candidateCount = (int) decisions.stream()
                .filter(decision -> decision.status() == BasicDecisionStatus.CANDIDATE)
                .count();
        if (candidateCount == 0) {
            return decisions;
        }
        EqualAllocationShare share = new EqualAllocationShare(1, candidateCount);
        return decisions.stream()
                .map(decision -> decision.status() == BasicDecisionStatus.CANDIDATE
                        ? decision.withBuyAllocation(share)
                        : decision)
                .toList();
    }
}
