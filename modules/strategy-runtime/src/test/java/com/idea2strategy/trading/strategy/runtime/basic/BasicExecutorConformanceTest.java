package com.idea2strategy.trading.strategy.runtime.basic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.trading.strategy.runtime.control.StrategyBotExecutionPlanAdapter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs the executor against the language-neutral D92 conformance fixture that the Python backtest
 * runtime is also checked against, so "the two runtimes are semantically equivalent" is a test
 * result rather than a claim. The fixture is a digest-pinned vendored copy of
 * {@code backtest-engine/conformance/strategy-bot-runtime/v1/basic-executor-conformance.v1.json};
 * the digest is asserted before anything is parsed, so both bindings always read the same bytes.
 */
class BasicExecutorConformanceTest {

    private static final String FIXTURE_RESOURCE =
            "/conformance/strategy-bot-runtime/v1/basic-executor-conformance.v1.json";

    /** The digest recorded next to the fixture in backtest-engine. Changes only with the fixture. */
    private static final String RECORDED_SHA256 =
            "2e7e29c9f6dc90ed53c1edf75887d9e4ee209af02db88781bd22421914fb74d5";

    private static final String IMPLEMENTATION_DEFINED = "$IMPLEMENTATION_DEFINED";

    @Test
    void theVendoredFixtureBytesMatchTheRecordedDigest() {
        assertEquals(RECORDED_SHA256, sha256(fixtureBytes()),
                "the vendored fixture drifted from the digest recorded in backtest-engine");
        String recordedFile = new String(
                resourceBytes(FIXTURE_RESOURCE + ".sha256"), StandardCharsets.UTF_8);
        assertEquals(RECORDED_SHA256, recordedFile.split("\\s+")[0],
                "the vendored .sha256 record disagrees with the digest this test pins");
    }

    @Test
    void theFixtureDeclaresTheVersionsAndReasonCodesThisRuntimeImplements() {
        JsonNode fixture = fixture();
        assertEquals(StrategyBotExecutionPlanAdapter.RUNTIME_SCHEMA_VERSION,
                fixture.get("runtimeSchemaVersion").asText());
        JsonNode reasonCodes = fixture.get("reasonCodes");
        assertEquals("CONDITION_EVALUATION_ERROR", reasonCodes.get("conditionError").asText());
        assertEquals("INSTRUMENT_INPUT_MISSING", reasonCodes.get("inputMissing").asText());
        assertEquals("$input", reasonCodes.get("missingInputStepId").asText());
        assertEquals("UNSIGNED_128BIT_BIG_ENDIAN",
                fixture.get("ordering").get("instrumentComparisonRule").asText());
    }

    @TestFactory
    Stream<DynamicTest> executorCasesProduceTheExpectedDecisions() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode conformanceCase : fixture().get("executorCases")) {
            tests.add(DynamicTest.dynamicTest(
                    conformanceCase.get("caseId").asText(),
                    () -> runExecutorCase(conformanceCase)));
        }
        return tests.stream();
    }

    private void runExecutorCase(JsonNode conformanceCase) {
        JsonNode stepOutcomes = conformanceCase.get("stepOutcomes");
        List<UUID> evaluationOrder = new ArrayList<>();
        List<BasicFlow> flows = new ArrayList<>();
        for (JsonNode flowNode : conformanceCase.get("flows")) {
            flows.add(scriptedFlow(flowNode, stepOutcomes, evaluationOrder));
        }
        Map<UUID, BasicInstrumentInput> inputs = new HashMap<>();
        for (JsonNode flowNode : conformanceCase.get("flows")) {
            for (JsonNode instrumentNode : flowNode.get("instrumentIds")) {
                UUID instrumentId = UUID.fromString(instrumentNode.asText());
                inputs.putIfAbsent(instrumentId, new BasicInstrumentInput(instrumentId, Map.of()));
            }
        }
        JsonNode withoutInput = conformanceCase.get("instrumentsWithoutInput");
        if (withoutInput != null) {
            for (JsonNode instrumentNode : withoutInput) {
                inputs.remove(UUID.fromString(instrumentNode.asText()));
            }
        }

        BasicExecutionResult result = new BasicStrategyExecutor().execute(new BasicExecutionRequest(
                UUID.fromString("4f7314fa-2af0-421f-93b7-fbc61de94a71"), flows, inputs));

        JsonNode expectedOrder = conformanceCase.get("expectedEvaluationOrder");
        if (expectedOrder != null) {
            List<UUID> expected = new ArrayList<>();
            expectedOrder.forEach(node -> expected.add(UUID.fromString(node.asText())));
            assertEquals(expected, evaluationOrder, "instrument evaluation order");
        }
        assertDecisionsMatch(conformanceCase.get("expectedDecisions"), result.decisions());
    }

    /**
     * One flow whose condition steps replay the case's scripted outcomes: each evaluator call for
     * an instrument consumes that instrument's next scripted entry, restarting from the beginning
     * of the script in every flow the instrument appears in.
     */
    private BasicFlow scriptedFlow(JsonNode flowNode, JsonNode stepOutcomes, List<UUID> evaluationOrder) {
        List<UUID> instrumentIds = new ArrayList<>();
        flowNode.get("instrumentIds").forEach(node -> instrumentIds.add(UUID.fromString(node.asText())));
        Map<UUID, Iterator<JsonNode>> scripts = new HashMap<>();
        for (UUID instrumentId : instrumentIds) {
            JsonNode script = stepOutcomes.get(instrumentId.toString());
            if (script != null) {
                scripts.put(instrumentId, script.iterator());
            }
        }
        Function<BasicInstrumentInput, BasicConditionOutcome> evaluator = input -> {
            UUID instrumentId = input.instrumentId();
            if (!evaluationOrder.contains(instrumentId)) {
                evaluationOrder.add(instrumentId);
            }
            Iterator<JsonNode> script = scripts.get(instrumentId);
            if (script == null || !script.hasNext()) {
                return fail("no scripted outcome left for instrument " + instrumentId);
            }
            JsonNode scripted = script.next();
            String outcome = scripted.get("outcome").asText();
            return switch (outcome) {
                case "PASSED" -> new BasicConditionOutcome(
                        true, scripted.get("reasonCode").asText(), textMap(scripted.get("evidence")));
                case "FAILED" -> new BasicConditionOutcome(
                        false, scripted.get("reasonCode").asText(), textMap(scripted.get("evidence")));
                case "RAISES_EVALUATION_ERROR" -> throw new IllegalStateException(
                        "scripted evaluation error for " + instrumentId);
                case "MUST_NOT_BE_EVALUATED" -> fail(
                        "instrument " + instrumentId + " was evaluated past its short circuit");
                default -> fail("unknown scripted outcome " + outcome);
            };
        };
        List<BasicConditionStep> steps = new ArrayList<>();
        for (JsonNode stepIdNode : flowNode.get("conditionSteps")) {
            steps.add(new BasicConditionStep(stepIdNode.asText(), evaluator));
        }
        return new BasicFlow(
                flowNode.get("flowId").asText(),
                BasicOrderSide.valueOf(flowNode.get("side").asText()),
                instrumentIds,
                steps);
    }

    private void assertDecisionsMatch(JsonNode expectedDecisions, List<BasicInstrumentDecision> actual) {
        assertEquals(expectedDecisions.size(), actual.size(), "decision count");
        for (int index = 0; index < actual.size(); index++) {
            JsonNode expected = expectedDecisions.get(index);
            BasicInstrumentDecision decision = actual.get(index);
            String at = "decision[" + index + "] ";
            assertEquals(expected.get("flowId").asText(), decision.flowId(), at + "flowId");
            assertEquals(UUID.fromString(expected.get("instrumentId").asText()),
                    decision.instrumentId(), at + "instrumentId");
            assertEquals(BasicOrderSide.valueOf(expected.get("side").asText()),
                    decision.side(), at + "side");
            assertEquals(BasicDecisionStatus.valueOf(expected.get("status").asText()),
                    decision.status(), at + "status");
            assertEquals(optionalText(expected.get("firstFailureStepId")),
                    decision.firstFailureStepId(), at + "firstFailureStepId");
            assertEquals(optionalText(expected.get("firstFailureReason")),
                    decision.firstFailureReason(), at + "firstFailureReason");
            JsonNode allocation = expected.get("buyAllocation");
            if (allocation == null || allocation.isNull()) {
                assertEquals(Optional.empty(), decision.buyAllocation(), at + "buyAllocation");
            } else {
                assertEquals(Optional.of(new EqualAllocationShare(
                                allocation.get("numerator").asInt(),
                                allocation.get("denominator").asInt())),
                        decision.buyAllocation(), at + "buyAllocation");
            }
            assertTraceMatches(expected.get("trace"), decision.trace(), at);
        }
    }

    private void assertTraceMatches(JsonNode expectedTrace, List<BasicStepTrace> actual, String at) {
        assertEquals(expectedTrace.size(), actual.size(), at + "trace length");
        for (int index = 0; index < actual.size(); index++) {
            JsonNode expected = expectedTrace.get(index);
            BasicStepTrace entry = actual.get(index);
            String entryAt = at + "trace[" + index + "] ";
            assertEquals(expected.get("stepId").asText(), entry.stepId(), entryAt + "stepId");
            assertEquals(expected.get("passed").asBoolean(), entry.passed(), entryAt + "passed");
            assertEquals(expected.get("reasonCode").asText(), entry.reasonCode(), entryAt + "reasonCode");
            JsonNode expectedEvidence = expected.get("evidence");
            expectedEvidence.fields().forEachRemaining(field -> {
                String actualValue = entry.evidence().get(field.getKey());
                if (IMPLEMENTATION_DEFINED.equals(field.getValue().asText())) {
                    assertTrue(actualValue != null && !actualValue.isBlank(),
                            entryAt + "evidence key " + field.getKey() + " must be present and non-empty");
                } else {
                    assertEquals(field.getValue().asText(), actualValue,
                            entryAt + "evidence key " + field.getKey());
                }
            });
            JsonNode additionalPermitted = expected.get("additionalEvidenceKeysPermitted");
            if (additionalPermitted == null || !additionalPermitted.asBoolean()) {
                assertEquals(textMap(expectedEvidence).keySet(), entry.evidence().keySet(),
                        entryAt + "evidence keys");
            }
        }
    }

    private static Optional<String> optionalText(JsonNode node) {
        return node == null || node.isNull() ? Optional.empty() : Optional.of(node.asText());
    }

    private static Map<String, String> textMap(JsonNode node) {
        Map<String, String> values = new LinkedHashMap<>();
        node.fields().forEachRemaining(field -> values.put(field.getKey(), field.getValue().asText()));
        return values;
    }

    private static JsonNode fixture() {
        try {
            return new ObjectMapper().readTree(fixtureBytes());
        } catch (IOException failure) {
            throw new UncheckedIOException("unable to parse the conformance fixture", failure);
        }
    }

    private static byte[] fixtureBytes() {
        byte[] bytes = resourceBytes(FIXTURE_RESOURCE);
        assertEquals(RECORDED_SHA256, sha256(bytes),
                "refusing to parse a fixture whose bytes do not match the recorded digest");
        return bytes;
    }

    private static byte[] resourceBytes(String resource) {
        try (InputStream stream = BasicExecutorConformanceTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                return fail("missing test resource " + resource);
            }
            return stream.readAllBytes();
        } catch (IOException failure) {
            throw new UncheckedIOException("unable to read " + resource, failure);
        }
    }

    private static String sha256(byte[] bytes) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required", unavailable);
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest(bytes)) {
            hex.append(Character.forDigit((value >> 4) & 0xF, 16));
            hex.append(Character.forDigit(value & 0xF, 16));
        }
        return hex.toString();
    }
}
