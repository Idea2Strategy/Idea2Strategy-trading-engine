package com.idea2strategy.trading.strategy.runtime.basic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class BasicStrategyExecutor {
    private static final String INPUT_MISSING = "INSTRUMENT_INPUT_MISSING";
    private static final String CONDITION_ERROR = "CONDITION_EVALUATION_ERROR";

    /**
     * The conformance ordering rule UNSIGNED_128BIT_BIG_ENDIAN: the two UUIDs compare as 16-byte
     * big-endian unsigned integers, matching the canonical text form and PostgreSQL's uuid type.
     * {@link UUID#compareTo} is not usable here because it compares the two halves as SIGNED longs,
     * so any id whose first byte is {@code >= 0x80} would sort before every lower id.
     */
    static final Comparator<UUID> UNSIGNED_128BIT_BIG_ENDIAN = (left, right) -> {
        int mostSignificant = Long.compareUnsigned(
                left.getMostSignificantBits(), right.getMostSignificantBits());
        if (mostSignificant != 0) {
            return mostSignificant;
        }
        return Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
    };

    public BasicExecutionResult execute(BasicExecutionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        List<BasicInstrumentDecision> decisions = new ArrayList<>();
        for (BasicFlow flow : request.flows()) {
            List<BasicInstrumentDecision> flowDecisions = flow.instrumentIds().stream()
                    .sorted(UNSIGNED_128BIT_BIG_ENDIAN)
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
        if (!instrumentId.equals(input.instrumentId())) {
            throw new IllegalArgumentException(
                    "instrument input keyed as " + instrumentId + " belongs to " + input.instrumentId());
        }
        List<BasicStepTrace> trace = new ArrayList<>();
        for (BasicConditionStep step : flow.conditionSteps()) {
            try {
                BasicConditionOutcome outcome = Objects.requireNonNull(
                        step.evaluate(input), "condition outcome must not be null");
                trace.add(new BasicStepTrace(step.stepId(), outcome.passed(), outcome.reasonCode(), outcome.evidence()));
                if (!outcome.passed()) {
                    return rejected(flow, instrumentId, BasicDecisionStatus.CONDITION_NOT_MET, trace,
                            step.stepId(), outcome.reasonCode());
                }
            } catch (BasicInputMissingException missing) {
                Map<String, String> evidence = new LinkedHashMap<>(missing.evidence());
                evidence.put("inputReason", missing.inputReason());
                trace.add(new BasicStepTrace(step.stepId(), false, INPUT_MISSING, evidence));
                return rejected(flow, instrumentId, BasicDecisionStatus.INPUT_MISSING, trace,
                        step.stepId(), INPUT_MISSING);
            } catch (RuntimeException failure) {
                trace.add(new BasicStepTrace(step.stepId(), false, CONDITION_ERROR,
                        Map.of("errorType", failure.getClass().getSimpleName())));
                return rejected(flow, instrumentId, BasicDecisionStatus.CONDITION_ERROR, trace,
                        step.stepId(), CONDITION_ERROR);
            }
        }
        return new BasicInstrumentDecision(
                flow.flowId(), instrumentId, flow.side(), BasicDecisionStatus.CANDIDATE,
                trace, Optional.empty(), Optional.empty(), Optional.empty());
    }

    private BasicInstrumentDecision rejected(
            BasicFlow flow,
            UUID instrumentId,
            BasicDecisionStatus status,
            List<BasicStepTrace> trace,
            String failureStepId,
            String failureReason) {
        return new BasicInstrumentDecision(
                flow.flowId(), instrumentId, flow.side(), status, trace,
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
