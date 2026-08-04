package com.idea2strategy.trading.strategy.runtime.plan;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idea2strategy.trading.strategy.runtime.basic.BasicDecisionStatus;
import com.idea2strategy.trading.strategy.runtime.basic.BasicExecutionRequest;
import com.idea2strategy.trading.strategy.runtime.basic.BasicExecutionResult;
import com.idea2strategy.trading.strategy.runtime.basic.BasicInstrumentInput;
import com.idea2strategy.trading.strategy.runtime.basic.BasicOrderSide;
import com.idea2strategy.trading.strategy.runtime.basic.BasicStrategyExecutor;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * B's published plan document, executed.
 *
 * <p>The document is the shape B publishes on {@code strategy-bot.v1}, and the assertions are about
 * the semantics D's element catalog defines: the RSI is loaded, compared against an exact decimal
 * threshold, and a candidate is emitted only when the comparison passes. The two divergences root
 * #142 settled are pinned here from the interpreter's side — a missing feature value is
 * INPUT_MISSING rather than CONDITION_ERROR, and step ids are the {@code step-<n>:<OPERATION>}
 * identifiers both runtimes write into their traces.
 */
class BasicPlanInterpreterTest {

    private static final UUID INSTRUMENT = UUID.fromString("00000000-0000-4000-8000-000000000301");
    private static final UUID SECOND_INSTRUMENT = UUID.fromString("00000000-0000-4000-8000-000000000302");
    private static final UUID EVALUATION = UUID.fromString("00000000-0000-4000-8000-000000000901");

    private final BasicPlanInterpreter interpreter = new BasicPlanInterpreter();
    private final BasicStrategyExecutor executor = new BasicStrategyExecutor();

    @Test
    void readsTheFlowsSideAndInstrumentsBPublished() {
        var plan = interpreter.interpret(planDocument("LT", "30", INSTRUMENT));

        assertAll(
                () -> assertEquals(1, plan.flows().size()),
                () -> assertEquals("flow-1", plan.flows().getFirst().flowId()),
                // The side belongs to the flow, not the plan: a strategy has one container per side.
                () -> assertEquals(BasicOrderSide.BUY, plan.flows().getFirst().side()),
                () -> assertEquals(List.of(INSTRUMENT), plan.flows().getFirst().instrumentIds()),
                () -> assertEquals(Map.of("flow-1", "partition-1"), plan.partitionKeyByFlowKey()),
                () -> assertEquals(java.util.Set.of(INSTRUMENT), plan.subscribedInstruments()),
                // The terminal EMIT_ORDER_CANDIDATE is consumed by the loader, never evaluated.
                () -> assertEquals(2, plan.flows().getFirst().conditionSteps().size()),
                () -> assertEquals("step-1:LOAD_FEATURE",
                        plan.flows().getFirst().conditionSteps().getFirst().stepId()),
                () -> assertEquals("step-2:COMPARE",
                        plan.flows().getFirst().conditionSteps().getLast().stepId()));
    }

    @Test
    void emitsACandidateOnlyWhenTheComparisonPasses() {
        var plan = interpreter.interpret(planDocument("LT", "30", INSTRUMENT));

        BasicExecutionResult below = execute(plan, "28.50000000");
        BasicExecutionResult above = execute(plan, "31.00000000");

        assertAll(
                () -> assertEquals(BasicDecisionStatus.CANDIDATE, below.decisions().getFirst().status()),
                () -> assertEquals("COMPARE_TRUE",
                        below.decisions().getFirst().trace().getLast().reasonCode()),
                () -> assertEquals(BasicDecisionStatus.CONDITION_NOT_MET,
                        above.decisions().getFirst().status()),
                () -> assertEquals("COMPARE_FALSE",
                        above.decisions().getFirst().trace().getLast().reasonCode()));
    }

    /** The threshold is an exact decimal, so the boundary is not a floating-point coin flip. */
    @Test
    void comparesExactlyAtTheThreshold() {
        var strictlyLess = interpreter.interpret(planDocument("LT", "30", INSTRUMENT));
        var lessOrEqual = interpreter.interpret(planDocument("LTE", "30", INSTRUMENT));

        assertAll(
                () -> assertEquals(BasicDecisionStatus.CONDITION_NOT_MET,
                        execute(strictlyLess, "30.00000000").decisions().getFirst().status()),
                () -> assertEquals(BasicDecisionStatus.CANDIDATE,
                        execute(lessOrEqual, "30.00000000").decisions().getFirst().status()),
                () -> assertEquals(BasicDecisionStatus.CANDIDATE,
                        execute(strictlyLess, "29.99999999").decisions().getFirst().status()));
    }

    /**
     * Root #142's material divergence, from this side: a feature whose warm-up has not completed is
     * INPUT_MISSING, never CONDITION_ERROR, so anything counting failures by status agrees with D.
     */
    @Test
    void classifiesAnAbsentFeatureValueAsInputMissing() {
        var plan = interpreter.interpret(planDocument("LT", "30", INSTRUMENT));

        BasicExecutionResult result = executor.execute(new BasicExecutionRequest(
                EVALUATION, plan.flows(),
                Map.of(INSTRUMENT, new BasicInstrumentInput(INSTRUMENT, Map.of()))));

        var decision = result.decisions().getFirst();
        assertAll(
                () -> assertEquals(BasicDecisionStatus.INPUT_MISSING, decision.status()),
                () -> assertEquals("step-1:LOAD_FEATURE",
                        decision.firstFailureStepId().orElseThrow()),
                () -> assertTrue(decision.trace().getFirst().evidence()
                        .containsKey("requiredBars")));
    }

    /** Instruments of one flow are evaluated independently, in UUID order. */
    @Test
    void evaluatesEachInstrumentIndependently() {
        var plan = interpreter.interpret(planDocument("LT", "30", INSTRUMENT, SECOND_INSTRUMENT));

        BasicExecutionResult result = executor.execute(new BasicExecutionRequest(
                EVALUATION, plan.flows(),
                Map.of(
                        INSTRUMENT, input(INSTRUMENT, "28.00000000"),
                        SECOND_INSTRUMENT, input(SECOND_INSTRUMENT, "45.00000000"))));

        assertAll(
                () -> assertEquals(2, result.decisions().size()),
                () -> assertEquals(BasicDecisionStatus.CANDIDATE, result.decisions().stream()
                        .filter(decision -> decision.instrumentId().equals(INSTRUMENT))
                        .findFirst().orElseThrow().status()),
                () -> assertEquals(BasicDecisionStatus.CONDITION_NOT_MET, result.decisions().stream()
                        .filter(decision -> decision.instrumentId().equals(SECOND_INSTRUMENT))
                        .findFirst().orElseThrow().status()));
    }

    @Test
    void refusesAPlanItCannotExecute() {
        assertAll(
                // A version 2 document whose flows carry no steps: the version says the steps are
                // per container, and there are none, so it is refused rather than falling back to
                // the plan-level list a version 1 document would have.
                () -> assertThrows(IllegalArgumentException.class,
                        () -> interpreter.interpret(planDocument("LT", "30", INSTRUMENT)
                                .replace("basic-compiled-plan.v1", "basic-compiled-plan.v2"))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> interpreter.interpret(planDocument("LT", "30", INSTRUMENT)
                                .replace("basic-compiled-plan.v1", "basic-compiled-plan.v3"))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> interpreter.interpret(planDocument("LT", "30", INSTRUMENT)
                                .replace("\"RSI_14\"", "\"WILDERS_RSI_14\""))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> interpreter.interpret(planDocument("LT", "30", INSTRUMENT)
                                .replace("\"EMIT_ORDER_CANDIDATE\"", "\"COMPARE\""))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> interpreter.interpret("{\"schemaVersion\":\"basic-compiled-plan.v1\"}")));
    }

    // ------------------------------------------------------------------ fixtures

    private BasicExecutionResult execute(
            BasicPlanInterpreter.InterpretedPlan plan, String featureValue) {
        return executor.execute(new BasicExecutionRequest(
                EVALUATION, plan.flows(), Map.of(INSTRUMENT, input(INSTRUMENT, featureValue))));
    }

    private static BasicInstrumentInput input(UUID instrumentId, String rsi) {
        return new BasicInstrumentInput(instrumentId, Map.of("RSI_14", rsi));
    }

    /** The document shape B publishes, with the comparison and instruments varied. */
    private static String planDocument(String operator, String threshold, UUID... instruments) {
        String ids = java.util.Arrays.stream(instruments)
                .map(id -> "\"" + id + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        return """
                {"contractVersion":"strategy-bot.v1","schemaVersion":"basic-compiled-plan.v1",
                "elementCatalogVersion":"basic-elements:2026-07-31",
                "instrumentCatalogVersion":"us-supported-universe:2026-07-31",
                "compilerVersion":"basic-compiler:1.0.0",
                "requiredFeatureSetHash":"sha256:%s","requiredFeatures":[{"requirementId":"rsi-14-pt1m",
                "featureId":"00000000-0000-4000-8000-000000000401","featureVersion":"1.0.0",
                "instruments":[%s],"resolution":"PT1M","requiredObservations":14}],
                "executionSnapshot":{"immutableStrategyVersion":{
                "snapshotSchemaVersion":"basic-launch-snapshot.v1","semanticHash":"sha256:%s",
                "snapshotHash":"sha256:%s"},"mode":"BASIC","initialCashAmount":"100000.00000000",
                "currency":"USD","partitions":[{"key":"partition-1","budgetCapBps":10000,
                "flows":[{"key":"flow-1","officialInstrumentIds":[%s]}]}]},
                "steps":[{"sequence":1,"operation":"LOAD_FEATURE",
                "arguments":{"feature":"RSI_14","resolution":"1m"}},{"sequence":2,"operation":"COMPARE",
                "arguments":{"operator":"%s","threshold":"%s"}},{"sequence":3,
                "operation":"EMIT_ORDER_CANDIDATE",
                "arguments":{"allocation":"EQUAL","orderType":"MARKET","side":"BUY"}}],
                "planChecksum":"sha256:%s"}
                """.formatted("3".repeat(64), ids, "2".repeat(64), "1".repeat(64), ids,
                        operator, threshold, "4".repeat(64));
    }

    /**
     * Root #202: the ordinary Basic strategy — a buy container and a sell container over the same
     * instrument, each an AND chain of its own blocks.
     *
     * <p>The buy container reads RSI_14 and buys below 30; the sell container reads the same feature and
     * sells above 70. Version 1 had no shape for this, which is why the release refused it.
     */
    @Test
    void readsOneContainerPerSideFromAVersionTwoPlan() {
        var plan = interpreter.interpret(twoContainerPlanDocument());

        assertAll(
                () -> assertEquals(2, plan.flows().size()),
                () -> assertEquals("buy", plan.flows().getFirst().flowId()),
                () -> assertEquals(BasicOrderSide.BUY, plan.flows().getFirst().side()),
                () -> assertEquals("sell", plan.flows().getLast().flowId()),
                () -> assertEquals(BasicOrderSide.SELL, plan.flows().getLast().side()),
                () -> assertEquals(Map.of("buy", "partition-1", "sell", "partition-1"),
                        plan.partitionKeyByFlowKey()),
                () -> assertEquals(java.util.Set.of(INSTRUMENT), plan.subscribedInstruments()),
                // Each container keeps its own chain; the terminal step is consumed, never evaluated.
                () -> assertEquals(2, plan.flows().getFirst().conditionSteps().size()),
                () -> assertEquals(2, plan.flows().getLast().conditionSteps().size()));
    }

    /**
     * The AND inside a container: three blocks, and the container decides only when all of them hold.
     *
     * <p>Asserted through the executor rather than the interpreter, because "AND" is a claim about
     * evaluation. RSI 25 satisfies {@code < 30} and {@code < 40} but not {@code < 20}, so the container
     * declines; RSI 15 satisfies all three and it emits.
     */
    @Test
    void aContainerDecidesOnlyWhenEveryBlockInItHolds() {
        var flow = interpreter.interpret(andChainPlanDocument()).flows().getFirst();

        var declined = executor.execute(new BasicExecutionRequest(
                EVALUATION, List.of(flow), Map.of(INSTRUMENT, input(INSTRUMENT, "25"))));
        var emitted = executor.execute(new BasicExecutionRequest(
                EVALUATION, List.of(flow), Map.of(INSTRUMENT, input(INSTRUMENT, "15"))));

        assertAll(
                () -> assertEquals(BasicDecisionStatus.CONDITION_NOT_MET,
                        declined.decisions().getFirst().status(),
                        "one unmet block is enough to decline the whole container"),
                () -> assertEquals(BasicDecisionStatus.CANDIDATE,
                        emitted.decisions().getFirst().status()));
    }

    private static String twoContainerPlanDocument() {
        return """
                {"contractVersion":"strategy-bot.v1","schemaVersion":"basic-compiled-plan.v2",\
                "elementCatalogVersion":"basic-elements:2026-08-04",\
                "instrumentCatalogVersion":"us-supported-universe:2026-08-04",\
                "compilerVersion":"basic-compiler:1.0.0",\
                "requiredFeatureSetHash":"sha256:%s","requiredFeatures":[{"requirementId":"rsi-14-pt1m",\
                "featureId":"00000000-0000-4000-8000-000000000401","featureVersion":"1.0.0",\
                "instruments":["%s"],"resolution":"PT1M","requiredObservations":14}],\
                "executionSnapshot":{"immutableStrategyVersion":{\
                "snapshotSchemaVersion":"basic-launch-snapshot.v1","semanticHash":"sha256:%s",\
                "snapshotHash":"sha256:%s"},"mode":"BASIC","initialCashAmount":"100000.00000000",\
                "currency":"USD","partitions":[{"key":"partition-1","budgetCapBps":10000,"flows":[\
                {"key":"buy","officialInstrumentIds":["%s"],"steps":[\
                {"sequence":1,"operation":"LOAD_FEATURE",\
                "arguments":{"feature":"RSI_14","resolution":"1m"}},\
                {"sequence":2,"operation":"COMPARE","arguments":{"operator":"LT","threshold":"30"}},\
                {"sequence":3,"operation":"EMIT_ORDER_CANDIDATE",\
                "arguments":{"allocation":"EQUAL","orderType":"MARKET","side":"BUY"}}]},\
                {"key":"sell","officialInstrumentIds":["%s"],"steps":[\
                {"sequence":1,"operation":"LOAD_FEATURE",\
                "arguments":{"feature":"RSI_14","resolution":"1m"}},\
                {"sequence":2,"operation":"COMPARE","arguments":{"operator":"GT","threshold":"70"}},\
                {"sequence":3,"operation":"EMIT_ORDER_CANDIDATE",\
                "arguments":{"allocation":"EQUAL","orderType":"MARKET","side":"SELL"}}]}]}]},\
                "planChecksum":"sha256:%s"}"""
                .formatted("3".repeat(64), INSTRUMENT, "2".repeat(64), "1".repeat(64),
                        INSTRUMENT, INSTRUMENT, "4".repeat(64));
    }

    private static String andChainPlanDocument() {
        return """
                {"contractVersion":"strategy-bot.v1","schemaVersion":"basic-compiled-plan.v2",\
                "elementCatalogVersion":"basic-elements:2026-08-04",\
                "instrumentCatalogVersion":"us-supported-universe:2026-08-04",\
                "compilerVersion":"basic-compiler:1.0.0",\
                "requiredFeatureSetHash":"sha256:%s","requiredFeatures":[{"requirementId":"rsi-14-pt1m",\
                "featureId":"00000000-0000-4000-8000-000000000401","featureVersion":"1.0.0",\
                "instruments":["%s"],"resolution":"PT1M","requiredObservations":14}],\
                "executionSnapshot":{"immutableStrategyVersion":{\
                "snapshotSchemaVersion":"basic-launch-snapshot.v1","semanticHash":"sha256:%s",\
                "snapshotHash":"sha256:%s"},"mode":"BASIC","initialCashAmount":"100000.00000000",\
                "currency":"USD","partitions":[{"key":"partition-1","budgetCapBps":10000,"flows":[\
                {"key":"buy","officialInstrumentIds":["%s"],"steps":[\
                {"sequence":1,"operation":"LOAD_FEATURE",\
                "arguments":{"feature":"RSI_14","resolution":"1m"}},\
                {"sequence":2,"operation":"COMPARE","arguments":{"operator":"LT","threshold":"30"}},\
                {"sequence":3,"operation":"COMPARE","arguments":{"operator":"LT","threshold":"40"}},\
                {"sequence":4,"operation":"COMPARE","arguments":{"operator":"LT","threshold":"20"}},\
                {"sequence":5,"operation":"EMIT_ORDER_CANDIDATE",\
                "arguments":{"allocation":"EQUAL","orderType":"MARKET","side":"BUY"}}]}]}]},\
                "planChecksum":"sha256:%s"}"""
                .formatted("3".repeat(64), INSTRUMENT, "2".repeat(64), "1".repeat(64),
                        INSTRUMENT, "4".repeat(64));
    }
}
