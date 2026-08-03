package com.idea2strategy.trading.strategy.runtime.basic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BasicStrategyExecutorTest {
    private static final UUID EVALUATION_ID = UUID.fromString("4f7314fa-2af0-421f-93b7-fbc61de94a71");
    private static final UUID FIRST_INSTRUMENT = UUID.fromString("00000000-0000-4000-8000-000000000301");
    private static final UUID SECOND_INSTRUMENT = UUID.fromString("00000000-0000-4000-8000-000000000302");

    @Test
    void evaluatesEachInstrumentSequentiallyAndStopsAtItsFirstFailedCondition() {
        List<String> calls = new ArrayList<>();
        BasicFlow flow = new BasicFlow(
                "buy-flow",
                BasicOrderSide.BUY,
                List.of(SECOND_INSTRUMENT, FIRST_INSTRUMENT),
                List.of(
                        step("one", calls, input -> BasicConditionOutcome.passed("FEATURE_READY")),
                        step("two", calls, input -> input.instrumentId().equals(SECOND_INSTRUMENT)
                                ? BasicConditionOutcome.failed("THRESHOLD_NOT_MET")
                                : BasicConditionOutcome.passed("THRESHOLD_MET")),
                        step("three", calls, input -> BasicConditionOutcome.passed("FINAL_CONDITION_MET"))));
        BasicExecutionRequest request = request(flow, FIRST_INSTRUMENT, SECOND_INSTRUMENT);

        BasicExecutionResult result = new BasicStrategyExecutor().execute(request);

        assertEquals(List.of(FIRST_INSTRUMENT, SECOND_INSTRUMENT), result.decisions().stream()
                .map(BasicInstrumentDecision::instrumentId)
                .toList());
        BasicInstrumentDecision accepted = result.decisions().get(0);
        assertEquals(BasicDecisionStatus.CANDIDATE, accepted.status());
        assertEquals(new EqualAllocationShare(1, 1), accepted.buyAllocation().orElseThrow());
        assertEquals(3, accepted.trace().size());

        BasicInstrumentDecision rejected = result.decisions().get(1);
        assertEquals(BasicDecisionStatus.CONDITION_NOT_MET, rejected.status());
        assertEquals("two", rejected.firstFailureStepId().orElseThrow());
        assertEquals("THRESHOLD_NOT_MET", rejected.firstFailureReason().orElseThrow());
        assertEquals(2, rejected.trace().size());
        assertFalse(calls.contains("three:" + SECOND_INSTRUMENT));
    }

    @Test
    void isolatesConditionErrorsToTheAffectedInstrument() {
        BasicFlow flow = new BasicFlow(
                "buy-flow",
                BasicOrderSide.BUY,
                List.of(FIRST_INSTRUMENT, SECOND_INSTRUMENT),
                List.of(new BasicConditionStep("catalog-condition", input -> {
                    if (input.instrumentId().equals(FIRST_INSTRUMENT)) {
                        throw new IllegalStateException("catalog evaluator failed");
                    }
                    return BasicConditionOutcome.passed("CONDITION_MET");
                })));

        BasicExecutionResult result = new BasicStrategyExecutor().execute(
                request(flow, FIRST_INSTRUMENT, SECOND_INSTRUMENT));

        assertEquals(BasicDecisionStatus.CONDITION_ERROR, result.decisions().get(0).status());
        assertEquals("CONDITION_EVALUATION_ERROR", result.decisions().get(0).firstFailureReason().orElseThrow());
        assertEquals("IllegalStateException", result.decisions().get(0).trace().get(0).evidence().get("errorType"));
        assertEquals(BasicDecisionStatus.CANDIDATE, result.decisions().get(1).status());
        assertEquals(new EqualAllocationShare(1, 1), result.decisions().get(1).buyAllocation().orElseThrow());
    }

    @Test
    void assignsExactEqualBuyFractionsAndPreservesSellMeaning() {
        BasicConditionStep passing = new BasicConditionStep(
                "condition", input -> BasicConditionOutcome.passed("CONDITION_MET"));
        BasicFlow buy = new BasicFlow(
                "buy-flow", BasicOrderSide.BUY,
                List.of(SECOND_INSTRUMENT, FIRST_INSTRUMENT), List.of(passing));
        BasicFlow sell = new BasicFlow(
                "sell-flow", BasicOrderSide.SELL,
                List.of(FIRST_INSTRUMENT), List.of(passing));
        BasicExecutionRequest request = new BasicExecutionRequest(
                EVALUATION_ID,
                List.of(buy, sell),
                inputs(FIRST_INSTRUMENT, SECOND_INSTRUMENT));

        BasicExecutionResult result = new BasicStrategyExecutor().execute(request);

        assertEquals(new EqualAllocationShare(1, 2), result.decisions().get(0).buyAllocation().orElseThrow());
        assertEquals(new EqualAllocationShare(1, 2), result.decisions().get(1).buyAllocation().orElseThrow());
        assertEquals(BasicOrderSide.SELL, result.decisions().get(2).side());
        assertEquals(BasicDecisionStatus.CANDIDATE, result.decisions().get(2).status());
        assertTrue(result.decisions().get(2).buyAllocation().isEmpty());
    }

    @Test
    void recordsMissingInputsWithoutCreatingCandidatesAndReturnsImmutableResults() {
        BasicFlow flow = new BasicFlow(
                "buy-flow",
                BasicOrderSide.BUY,
                List.of(FIRST_INSTRUMENT, SECOND_INSTRUMENT),
                List.of(new BasicConditionStep(
                        "condition", input -> BasicConditionOutcome.passed("CONDITION_MET"))));
        BasicExecutionRequest request = new BasicExecutionRequest(
                EVALUATION_ID,
                List.of(flow),
                inputs(FIRST_INSTRUMENT));

        BasicExecutionResult result = new BasicStrategyExecutor().execute(request);

        assertEquals(BasicDecisionStatus.INPUT_MISSING, result.decisions().get(1).status());
        assertEquals("INSTRUMENT_INPUT_MISSING", result.decisions().get(1).firstFailureReason().orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> result.decisions().add(result.decisions().get(0)));
        assertThrows(UnsupportedOperationException.class,
                () -> result.decisions().get(0).trace().add(result.decisions().get(0).trace().get(0)));
    }

    @Test
    void ordersInstrumentsAsUnsigned128BitBigEndianNotAsSignedLongs() {
        UUID high = UUID.fromString("f0000000-0000-4000-8000-000000000001");
        UUID low = UUID.fromString("00000000-0000-4000-8000-000000000002");
        UUID signBoundary = UUID.fromString("80000000-0000-4000-8000-000000000003");
        UUID belowBoundary = UUID.fromString("7fffffff-ffff-4fff-8fff-000000000004");
        BasicFlow flow = new BasicFlow(
                "buy-flow",
                BasicOrderSide.BUY,
                List.of(high, low, signBoundary, belowBoundary),
                List.of(new BasicConditionStep(
                        "condition", input -> BasicConditionOutcome.passed("CONDITION_MET"))));

        BasicExecutionResult result = new BasicStrategyExecutor().execute(
                request(flow, high, low, signBoundary, belowBoundary));

        assertEquals(List.of(low, belowBoundary, signBoundary, high), result.decisions().stream()
                .map(BasicInstrumentDecision::instrumentId)
                .toList());
    }

    @Test
    void classifiesAMidStepMissingInputAsInputMissingWithTheRealFailingStep() {
        BasicFlow flow = new BasicFlow(
                "buy-flow",
                BasicOrderSide.BUY,
                List.of(FIRST_INSTRUMENT, SECOND_INSTRUMENT),
                List.of(
                        new BasicConditionStep(
                                "step-1:LOAD_FEATURE", input -> {
                                    if (input.instrumentId().equals(FIRST_INSTRUMENT)) {
                                        throw new BasicInputMissingException(
                                                "FEATURE_WARMUP_INCOMPLETE",
                                                Map.of("requiredBars", "15", "availableBars", "14"));
                                    }
                                    return BasicConditionOutcome.passed("FEATURE_LOADED");
                                }),
                        new BasicConditionStep(
                                "step-2:COMPARE", input -> {
                                    assertFalse(input.instrumentId().equals(FIRST_INSTRUMENT),
                                            "the step after a missing input must not run");
                                    return BasicConditionOutcome.passed("COMPARE_TRUE");
                                })));

        BasicExecutionResult result = new BasicStrategyExecutor().execute(
                request(flow, FIRST_INSTRUMENT, SECOND_INSTRUMENT));

        BasicInstrumentDecision starved = result.decisions().get(0);
        assertEquals(BasicDecisionStatus.INPUT_MISSING, starved.status());
        assertEquals("step-1:LOAD_FEATURE", starved.firstFailureStepId().orElseThrow());
        assertEquals("INSTRUMENT_INPUT_MISSING", starved.firstFailureReason().orElseThrow());
        assertEquals(1, starved.trace().size());
        BasicStepTrace failing = starved.trace().get(0);
        assertFalse(failing.passed());
        assertEquals("INSTRUMENT_INPUT_MISSING", failing.reasonCode());
        assertEquals("FEATURE_WARMUP_INCOMPLETE", failing.evidence().get("inputReason"));
        assertEquals("15", failing.evidence().get("requiredBars"));
        assertEquals("14", failing.evidence().get("availableBars"));
        assertEquals(BasicDecisionStatus.CANDIDATE, result.decisions().get(1).status());
        assertEquals(new EqualAllocationShare(1, 1), result.decisions().get(1).buyAllocation().orElseThrow());
    }

    @Test
    void failsClosedOnAMisKeyedInstrumentInputInsteadOfDecidingForAnotherInstrument() {
        assertThrows(IllegalArgumentException.class, () -> new BasicExecutionRequest(
                EVALUATION_ID,
                List.of(new BasicFlow(
                        "buy-flow",
                        BasicOrderSide.BUY,
                        List.of(FIRST_INSTRUMENT),
                        List.of(new BasicConditionStep(
                                "condition", input -> BasicConditionOutcome.passed("CONDITION_MET"))))),
                Map.of(FIRST_INSTRUMENT,
                        new BasicInstrumentInput(SECOND_INSTRUMENT, Map.of("price", "100.00")))));
    }

    private static BasicConditionStep step(
            String stepId,
            List<String> calls,
            java.util.function.Function<BasicInstrumentInput, BasicConditionOutcome> evaluator) {
        return new BasicConditionStep(stepId, input -> {
            calls.add(stepId + ":" + input.instrumentId());
            return evaluator.apply(input);
        });
    }

    private static BasicExecutionRequest request(BasicFlow flow, UUID... instrumentIds) {
        return new BasicExecutionRequest(EVALUATION_ID, List.of(flow), inputs(instrumentIds));
    }

    private static Map<UUID, BasicInstrumentInput> inputs(UUID... instrumentIds) {
        return java.util.Arrays.stream(instrumentIds)
                .collect(java.util.stream.Collectors.toMap(
                        instrumentId -> instrumentId,
                        instrumentId -> new BasicInstrumentInput(instrumentId, Map.of("price", "100.00"))));
    }
}
