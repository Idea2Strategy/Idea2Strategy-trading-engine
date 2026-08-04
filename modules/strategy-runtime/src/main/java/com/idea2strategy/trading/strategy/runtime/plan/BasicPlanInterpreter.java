package com.idea2strategy.trading.strategy.runtime.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.trading.strategy.runtime.basic.BasicConditionOutcome;
import com.idea2strategy.trading.strategy.runtime.basic.BasicConditionStep;
import com.idea2strategy.trading.strategy.runtime.basic.BasicFlow;
import com.idea2strategy.trading.strategy.runtime.basic.BasicInputMissingException;
import com.idea2strategy.trading.strategy.runtime.basic.BasicInstrumentInput;
import com.idea2strategy.trading.strategy.runtime.basic.BasicOrderSide;
import com.idea2strategy.trading.strategy.runtime.feature.OfficialFeature;
import com.idea2strategy.trading.strategy.runtime.feature.OfficialFeatureCatalog;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Turns B's published {@code basic-compiled-plan.v1} document into the flows the Basic executor runs.
 *
 * <p>The step vocabulary and its semantics are the ones D's element catalog defines, because D92
 * requires the same plan and the same inputs to reach the same decision in both runtimes:
 *
 * <ul>
 *   <li>{@code LOAD_FEATURE {feature, resolution}} — reads the named catalog feature's value for the
 *       instrument. A feature the build does not implement is refused when the plan is interpreted,
 *       not when it is evaluated. A feature whose warm-up has not completed raises
 *       {@link BasicInputMissingException}, so the decision is INPUT_MISSING rather than
 *       CONDITION_ERROR — the divergence root #142 settled.
 *   <li>{@code COMPARE {operator, threshold}} — compares the operand the preceding
 *       {@code LOAD_FEATURE} produced against an exact decimal threshold. Passing reports
 *       {@code COMPARE_TRUE}, failing {@code COMPARE_FALSE}.
 *   <li>{@code EMIT_ORDER_CANDIDATE} — terminal. The plan loader consumes it to learn the side and
 *       allocation; it is never evaluated per instrument, so it becomes no condition step.
 * </ul>
 *
 * <p>Step ids are {@code step-<sequence>:<operation>}, the identifiers D writes into its own step
 * trace, so a judgment log from either runtime names the same step.
 *
 * <p>An instrument's inputs arrive as the executor's {@code Map<String, String>}: this interpreter
 * reads a feature value under the feature id and treats an absent key as warm-up incomplete. Values
 * are parsed as {@link BigDecimal} — never {@code double} — because the catalog's contract value is
 * an exact 8-decimal figure.
 */
public final class BasicPlanInterpreter {

    /** The plan schema a single-container strategy still arrives on. */
    public static final String PLAN_SCHEMA_VERSION = "basic-compiled-plan.v1";

    /**
     * The plan schema a strategy with more than one trade container arrives on.
     *
     * <p>A Basic strategy is one container per side — a buy container and a sell container — and the
     * blocks inside a container are an AND chain: every condition has to hold before that container
     * emits. Version 1 could not express that, because it carried a single {@code steps} list and a
     * single side for the whole plan, so a strategy with both containers had no shape to be published
     * in and was refused at release (root #202). Version 2 moves {@code side}, {@code allocation} and
     * {@code steps} onto each flow, which is where they always belonged.
     *
     * <p>Version 1 is still read exactly as before, so every already-released bot keeps loading.
     */
    public static final String MULTI_CONTAINER_PLAN_SCHEMA_VERSION = "basic-compiled-plan.v2";

    private static final String LOAD_FEATURE = "LOAD_FEATURE";
    private static final String COMPARE = "COMPARE";
    private static final String EMIT_ORDER_CANDIDATE = "EMIT_ORDER_CANDIDATE";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Interprets a plan document into its flows, each with the side its own container declares.
     *
     * <p>Version 2 reads {@code side}, {@code allocation} and {@code steps} from each flow, which is
     * how a strategy with a buy container and a sell container is expressed. Version 1 reads them once
     * from the plan and gives every flow the same ones, which is what a single-container strategy
     * means and what every bot released before version 2 carries.
     */
    public InterpretedPlan interpret(String planDocument) {
        JsonNode root = parse(Objects.requireNonNull(planDocument, "planDocument"));
        String schemaVersion = text(root, "schemaVersion");
        boolean perFlow = MULTI_CONTAINER_PLAN_SCHEMA_VERSION.equals(schemaVersion);
        if (!perFlow && !PLAN_SCHEMA_VERSION.equals(schemaVersion)) {
            throw reject("plan schemaVersion " + schemaVersion + " is neither "
                    + PLAN_SCHEMA_VERSION + " nor " + MULTI_CONTAINER_PLAN_SCHEMA_VERSION);
        }

        CompiledContainer planWide = perFlow ? null : container(root, "plan");

        List<BasicFlow> flows = new ArrayList<>();
        Map<String, String> partitionKeyByFlowKey = new LinkedHashMap<>();
        JsonNode partitions = object(root, "executionSnapshot").get("partitions");
        if (partitions == null || !partitions.isArray() || partitions.isEmpty()) {
            throw reject("executionSnapshot.partitions must be a non-empty array");
        }
        for (JsonNode partition : partitions) {
            String partitionKey = text(partition, "key");
            JsonNode flowNodes = partition.get("flows");
            if (flowNodes == null || !flowNodes.isArray() || flowNodes.isEmpty()) {
                throw reject("partition " + partitionKey + " declares no flows");
            }
            for (JsonNode flowNode : flowNodes) {
                String flowKey = text(flowNode, "key");
                List<UUID> instruments = new ArrayList<>();
                JsonNode instrumentNodes = flowNode.get("officialInstrumentIds");
                if (instrumentNodes == null || !instrumentNodes.isArray() || instrumentNodes.isEmpty()) {
                    throw reject("flow " + flowKey + " declares no official instruments");
                }
                instrumentNodes.forEach(node -> instruments.add(UUID.fromString(node.asText())));
                CompiledContainer container =
                        perFlow ? container(flowNode, "flow " + flowKey) : planWide;
                flows.add(new BasicFlow(
                        flowKey, container.side(), instruments, container.conditionSteps()));
                if (partitionKeyByFlowKey.put(flowKey, partitionKey) != null) {
                    throw reject("flow key " + flowKey + " is declared more than once");
                }
            }
        }
        if (flows.isEmpty()) {
            throw reject("a compiled plan declares no flows");
        }
        return new InterpretedPlan(flows, Map.copyOf(partitionKeyByFlowKey));
    }

    /**
     * One container's compiled condition chain, its side and its allocation.
     *
     * <p>The chain is an AND: the executor runs the steps in order and stops at the first that does not
     * pass, so a container with three blocks buys only when all three hold. The terminal
     * {@code EMIT_ORDER_CANDIDATE} is not a condition — it is where the side and the allocation are
     * declared — so it is consumed here rather than evaluated per instrument.
     */
    private CompiledContainer container(JsonNode owner, String description) {
        List<PlanStep> steps = steps(owner);
        PlanStep terminal = steps.getLast();
        if (!EMIT_ORDER_CANDIDATE.equals(terminal.operation())) {
            throw reject(description + " must end with " + EMIT_ORDER_CANDIDATE);
        }
        List<PlanStep> conditionSteps = steps.subList(0, steps.size() - 1);
        if (conditionSteps.isEmpty()) {
            throw reject("an unconditional " + description + " would emit an order on every event");
        }
        List<BasicConditionStep> compiled = new ArrayList<>();
        for (PlanStep step : conditionSteps) {
            compiled.add(new BasicConditionStep(step.stepId(), evaluatorFor(step)));
        }
        return new CompiledContainer(
                BasicOrderSide.valueOf(argument(terminal, "side")),
                argument(terminal, "allocation"),
                List.copyOf(compiled));
    }

    private record CompiledContainer(
            BasicOrderSide side, String allocation, List<BasicConditionStep> conditionSteps) {}

    /**
     * The evaluator for one plan step.
     *
     * <p>{@code LOAD_FEATURE} publishes its value into the evaluation under the feature id so a later
     * {@code COMPARE} can read it. The executor gives every step the same immutable
     * {@link BasicInstrumentInput}, so the operand travels through that map rather than through
     * interpreter state — which is what keeps two instruments of the same flow from sharing anything.
     */
    private java.util.function.Function<BasicInstrumentInput, BasicConditionOutcome> evaluatorFor(
            PlanStep step) {
        return switch (step.operation()) {
            case LOAD_FEATURE -> {
                String featureId = argument(step, "feature");
                String resolution = argument(step, "resolution");
                OfficialFeature feature = OfficialFeatureCatalog.find(featureId)
                        .orElseThrow(() -> reject(
                                "feature " + featureId + " is not implemented by this build"));
                yield input -> {
                    String raw = input.values().get(feature.featureId());
                    if (raw == null || raw.isBlank()) {
                        throw new BasicInputMissingException("FEATURE_WARMUP_INCOMPLETE", Map.of(
                                "feature", feature.featureId(),
                                "featureVersion", feature.semanticVersion(),
                                "dataKind", feature.dataKind().name(),
                                "resolution", resolution,
                                "requiredBars", Integer.toString(feature.requiredBars())));
                    }
                    return new BasicConditionOutcome(true, "FEATURE_LOADED", Map.of(
                            "feature", feature.featureId(),
                            "featureVersion", feature.semanticVersion(),
                            "resolution", resolution,
                            "value", new BigDecimal(raw).toPlainString()));
                };
            }
            case COMPARE -> {
                ComparisonOperator operator = ComparisonOperator.valueOf(argument(step, "operator"));
                String rawThreshold = argument(step, "threshold");
                BigDecimal threshold = new BigDecimal(rawThreshold);
                yield input -> {
                    Operand operand = operandOf(input);
                    boolean passed = operator.test(operand.value().compareTo(threshold));
                    return new BasicConditionOutcome(passed,
                            passed ? "COMPARE_TRUE" : "COMPARE_FALSE",
                            Map.of(
                                    "operator", operator.name(),
                                    "operand", operand.value().toPlainString(),
                                    "threshold", rawThreshold,
                                    "source", operand.source()));
                };
            }
            default -> throw reject("operation " + step.operation() + " is not supported");
        };
    }

    /**
     * The operand a {@code COMPARE} reads: the value of the single catalog feature present in the
     * input. A compare with no loaded operand is a malformed plan, not a failed condition.
     */
    private static Operand operandOf(BasicInstrumentInput input) {
        for (var feature : OfficialFeatureCatalog.features().values()) {
            String raw = input.values().get(feature.featureId());
            if (raw != null && !raw.isBlank()) {
                return new Operand(new BigDecimal(raw), feature.featureId());
            }
        }
        throw new IllegalStateException("COMPARE has no operand: no feature was loaded before it");
    }

    private List<PlanStep> steps(JsonNode root) {
        JsonNode nodes = root.get("steps");
        if (nodes == null || !nodes.isArray() || nodes.isEmpty()) {
            throw reject("steps must be a non-empty array");
        }
        List<PlanStep> steps = new ArrayList<>();
        for (JsonNode node : nodes) {
            JsonNode sequence = node.get("sequence");
            if (sequence == null || !sequence.isInt()) {
                throw reject("every step needs an integer sequence");
            }
            steps.add(new PlanStep(sequence.asInt(), text(node, "operation"), node.get("arguments")));
        }
        // D orders by the declared sequence rather than by array position, so a plan whose array was
        // reordered still executes in the order B compiled it.
        steps.sort(java.util.Comparator.comparingInt(PlanStep::sequence));
        return steps;
    }

    private JsonNode parse(String document) {
        try {
            return objectMapper.readTree(document);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw reject("compiled plan is not valid JSON");
        }
    }

    private static JsonNode object(JsonNode parent, String name) {
        JsonNode value = parent == null ? null : parent.get(name);
        if (value == null || !value.isObject()) {
            throw reject(name + " must be an object");
        }
        return value;
    }

    private static String text(JsonNode parent, String name) {
        JsonNode value = parent == null ? null : parent.get(name);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw reject(name + " must be non-blank text");
        }
        return value.textValue();
    }

    private static String argument(PlanStep step, String name) {
        JsonNode arguments = step.arguments();
        if (arguments == null || !arguments.isObject()) {
            throw reject(step.operation() + " needs arguments");
        }
        return text(arguments, name);
    }

    private static IllegalArgumentException reject(String detail) {
        return new IllegalArgumentException("compiled plan is not executable: " + detail);
    }

    /**
     * What the interpreter produced from one plan document.
     *
     * <p>There is deliberately no plan-level side or allocation. A Basic strategy has one container per
     * side, so a single side for the whole plan could only ever describe one of them — that fiction is
     * what made a buy-and-sell strategy unpublishable (root #202). The side and the allocation belong to
     * the flow, which is what the executor runs and what each decision reports.
     */
    public record InterpretedPlan(
            List<BasicFlow> flows,
            Map<String, String> partitionKeyByFlowKey) {

        public InterpretedPlan {
            flows = List.copyOf(Objects.requireNonNull(flows, "flows"));
            partitionKeyByFlowKey = Map.copyOf(
                    Objects.requireNonNull(partitionKeyByFlowKey, "partitionKeyByFlowKey"));
            if (flows.isEmpty()) {
                throw new IllegalArgumentException("an interpreted plan carries at least one flow");
            }
        }

        /** Every instrument any flow of this plan evaluates. */
        public java.util.Set<UUID> subscribedInstruments() {
            java.util.Set<UUID> instruments = new java.util.LinkedHashSet<>();
            flows.forEach(flow -> instruments.addAll(flow.instrumentIds()));
            return java.util.Set.copyOf(instruments);
        }
    }

    /** The comparison operators D's catalog supports, with the same names. */
    private enum ComparisonOperator {
        LT, LTE, GT, GTE, EQ, NEQ;

        boolean test(int comparison) {
            return switch (this) {
                case LT -> comparison < 0;
                case LTE -> comparison <= 0;
                case GT -> comparison > 0;
                case GTE -> comparison >= 0;
                case EQ -> comparison == 0;
                case NEQ -> comparison != 0;
            };
        }
    }

    private record PlanStep(int sequence, String operation, JsonNode arguments) {
        String stepId() {
            return "step-" + sequence + ":" + operation;
        }
    }

    private record Operand(BigDecimal value, String source) {}
}
