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
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
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
    private static final MathContext MATH = new MathContext(18, RoundingMode.HALF_UP);

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
            case "PRICE_COMPARE" -> {
                String resolution = argument(step, "resolution");
                ComparisonOperator operator = ComparisonOperator.valueOf(argument(step, "operator"));
                String reference = argument(step, "reference");
                yield input -> withClosedBar(input, resolution, step.operation(), () -> {
                    List<BigDecimal> closes = series(input, "closes." + resolution, 1, step.operation());
                    BigDecimal price = last(closes);
                    BigDecimal comparedWith = referencePrice(input, resolution, reference, closes);
                    return compared(step.operation(), operator, price, comparedWith,
                            Map.of("reference", reference, "resolution", resolution));
                });
            }
            case "PRICE_CHANGE_PERCENT" -> {
                String resolution = argument(step, "resolution");
                String base = argument(step, "base");
                String direction = argument(step, "direction");
                BigDecimal threshold = decimalArgument(step, "thresholdPercent");
                yield input -> withClosedBar(input, resolution, step.operation(), () -> {
                    List<BigDecimal> closes = series(input, "closes." + resolution, 1, step.operation());
                    BigDecimal basePrice = referencePrice(input, resolution, base, closes);
                    BigDecimal change = percent(last(closes).subtract(basePrice), basePrice);
                    boolean passed = "UP".equals(direction)
                            ? change.compareTo(threshold) >= 0
                            : change.compareTo(threshold.negate()) <= 0;
                    return outcome(step.operation(), passed, Map.of(
                            "direction", direction, "changePercent", plain(change),
                            "thresholdPercent", threshold.toPlainString(), "base", base));
                });
            }
            case "VOLUME_COMPARE" -> {
                String resolution = argument(step, "resolution");
                ComparisonOperator operator = ComparisonOperator.valueOf(argument(step, "operator"));
                String reference = argument(step, "reference");
                int period = integerArgument(step, "period");
                BigDecimal multiplier = decimalArgument(step, "multiplier");
                yield input -> withClosedBar(input, resolution, step.operation(), () -> {
                    List<BigDecimal> volumes = series(input, "volumes." + resolution,
                            "PREVIOUS_VOLUME".equals(reference) ? 2 : period + 1, step.operation());
                    BigDecimal expected = "PREVIOUS_VOLUME".equals(reference)
                            ? volumes.get(volumes.size() - 2)
                            : average(volumes.subList(volumes.size() - period - 1, volumes.size() - 1));
                    expected = expected.multiply(multiplier, MATH);
                    return compared(step.operation(), operator, last(volumes), expected,
                            Map.of("reference", reference, "period", Integer.toString(period),
                                    "multiplier", multiplier.toPlainString()));
                });
            }
            case "STREAK" -> {
                String resolution = argument(step, "resolution");
                String direction = argument(step, "direction");
                int bars = integerArgument(step, "bars");
                yield input -> withClosedBar(input, resolution, step.operation(), () -> {
                    List<BigDecimal> closes = series(input, "closes." + resolution, bars + 1, step.operation());
                    int count = 0;
                    for (int index = closes.size() - 1; index > 0; index--) {
                        int comparison = closes.get(index).compareTo(closes.get(index - 1));
                        if (("UP".equals(direction) && comparison > 0)
                                || ("DOWN".equals(direction) && comparison < 0)) {
                            count++;
                        } else {
                            break;
                        }
                    }
                    return outcome(step.operation(), count >= bars,
                            Map.of("direction", direction, "streak", Integer.toString(count),
                                    "requiredBars", Integer.toString(bars)));
                });
            }
            case "SMA_CROSS" -> {
                String resolution = argument(step, "resolution");
                String direction = argument(step, "direction");
                int shortPeriod = integerArgument(step, "shortPeriod");
                int longPeriod = integerArgument(step, "longPeriod");
                yield input -> withClosedBar(input, resolution, step.operation(), () -> {
                    List<BigDecimal> closes = series(input, "closes." + resolution,
                            longPeriod + 1, step.operation());
                    BigDecimal currentShort = trailingAverage(closes, shortPeriod, 0);
                    BigDecimal currentLong = trailingAverage(closes, longPeriod, 0);
                    BigDecimal previousShort = trailingAverage(closes, shortPeriod, 1);
                    BigDecimal previousLong = trailingAverage(closes, longPeriod, 1);
                    boolean passed = crossed(direction, previousShort, previousLong,
                            currentShort, currentLong);
                    return outcome(step.operation(), passed, Map.of(
                            "direction", direction, "short", plain(currentShort),
                            "long", plain(currentLong)));
                });
            }
            case "RSI_CROSS" -> {
                String resolution = argument(step, "resolution");
                String direction = argument(step, "direction");
                int period = integerArgument(step, "period");
                BigDecimal threshold = decimalArgument(step, "threshold");
                yield input -> withClosedBar(input, resolution, step.operation(), () -> {
                    List<BigDecimal> closes = series(input, "closes." + resolution,
                            period + 2, step.operation());
                    BigDecimal current = rsi(closes, period, 0);
                    BigDecimal previous = rsi(closes, period, 1);
                    boolean passed = "UP".equals(direction)
                            ? previous.compareTo(threshold) <= 0 && current.compareTo(threshold) > 0
                            : previous.compareTo(threshold) >= 0 && current.compareTo(threshold) < 0;
                    return outcome(step.operation(), passed, Map.of(
                            "direction", direction, "previous", plain(previous),
                            "current", plain(current), "threshold", threshold.toPlainString()));
                });
            }
            case "MACD_CROSS" -> {
                String resolution = argument(step, "resolution");
                String direction = argument(step, "direction");
                int fast = integerArgument(step, "fastPeriod");
                int slow = integerArgument(step, "slowPeriod");
                int signal = integerArgument(step, "signalPeriod");
                yield input -> withClosedBar(input, resolution, step.operation(), () -> {
                    List<BigDecimal> closes = series(input, "closes." + resolution,
                            slow + signal + 2, step.operation());
                    List<BigDecimal> histogram = macdHistogram(closes, fast, slow, signal);
                    BigDecimal current = last(histogram);
                    BigDecimal previous = histogram.get(histogram.size() - 2);
                    boolean passed = crossed(direction, previous, BigDecimal.ZERO,
                            current, BigDecimal.ZERO);
                    return outcome(step.operation(), passed, Map.of(
                            "direction", direction, "previousHistogram", plain(previous),
                            "histogram", plain(current)));
                });
            }
            case "BOLLINGER_REVERSAL" -> {
                String resolution = argument(step, "resolution");
                String direction = argument(step, "direction");
                int period = integerArgument(step, "period");
                BigDecimal deviations = decimalArgument(step, "deviations");
                yield input -> withClosedBar(input, resolution, step.operation(), () -> {
                    List<BigDecimal> closes = series(input, "closes." + resolution,
                            period + 1, step.operation());
                    Band currentBand = band(closes, period, 0, deviations);
                    Band previousBand = band(closes, period, 1, deviations);
                    BigDecimal current = last(closes);
                    BigDecimal previous = closes.get(closes.size() - 2);
                    boolean passed = "UP".equals(direction)
                            ? previous.compareTo(previousBand.lower()) <= 0
                                    && current.compareTo(currentBand.lower()) > 0
                            : previous.compareTo(previousBand.upper()) >= 0
                                    && current.compareTo(currentBand.upper()) < 0;
                    return outcome(step.operation(), passed, Map.of(
                            "direction", direction, "previous", previous.toPlainString(),
                            "current", current.toPlainString()));
                });
            }
            case "POSITION_RETURN" -> {
                String direction = argument(step, "direction");
                BigDecimal threshold = decimalArgument(step, "thresholdPercent");
                yield input -> {
                    BigDecimal value = inputDecimal(input, "position.returnPercent", step.operation());
                    boolean passed = "PROFIT".equals(direction)
                            ? value.compareTo(threshold) >= 0
                            : value.compareTo(threshold.negate()) <= 0;
                    return outcome(step.operation(), passed, Map.of(
                            "direction", direction, "returnPercent", plain(value),
                            "thresholdPercent", threshold.toPlainString()));
                };
            }
            case "HOLDING_PERIOD" -> {
                String unit = argument(step, "unit");
                int amount = integerArgument(step, "amount");
                String resolution = argument(step, "resolution");
                yield input -> {
                    boolean passed = switch (unit) {
                        case "SESSION_CLOSE" -> Boolean.parseBoolean(input.values()
                                .getOrDefault("session.close", "false"));
                        case "BAR" -> inputDecimal(input,
                                "position.holdingBars." + resolution, step.operation())
                                .compareTo(BigDecimal.valueOf(amount)) >= 0;
                        case "TRADING_DAY" -> inputDecimal(input,
                                "position.holdingTradingDays", step.operation())
                                .compareTo(BigDecimal.valueOf(amount)) >= 0;
                        default -> throw reject("HOLDING_PERIOD unit " + unit + " is not supported");
                    };
                    return outcome(step.operation(), passed,
                            Map.of("unit", unit, "amount", Integer.toString(amount),
                                    "resolution", resolution));
                };
            }
            case "PEAK_RETURN", "DRAWDOWN_FROM_PEAK" -> {
                ComparisonOperator operator = ComparisonOperator.valueOf(argument(step, "operator"));
                BigDecimal threshold = decimalArgument(step, "thresholdPercent");
                String key = "PEAK_RETURN".equals(step.operation())
                        ? "position.peakReturnPercent" : "position.drawdownPercent";
                yield input -> compared(step.operation(), operator,
                        inputDecimal(input, key, step.operation()), threshold, Map.of());
            }
            case "SCHEDULE" -> {
                String cycle = argument(step, "cycle");
                int interval = integerArgument(step, "interval");
                yield input -> {
                    boolean newDay = Boolean.parseBoolean(input.values()
                            .getOrDefault("schedule.newTradingDay", "false"));
                    long dayIndex = Long.parseLong(input.values()
                            .getOrDefault("schedule.tradingDayIndex", "0"));
                    boolean passed = switch (cycle) {
                        case "EVERY_TRADING_DAY" -> newDay;
                        case "WEEK_FIRST_TRADING_DAY" -> Boolean.parseBoolean(input.values()
                                .getOrDefault("schedule.weekFirstTradingDay", "false"));
                        case "MONTH_FIRST_TRADING_DAY" -> Boolean.parseBoolean(input.values()
                                .getOrDefault("schedule.monthFirstTradingDay", "false"));
                        case "MONTH_LAST_TRADING_DAY" -> Boolean.parseBoolean(input.values()
                                .getOrDefault("schedule.monthLastTradingDay", "false"));
                        case "EVERY_N_TRADING_DAYS" -> newDay && interval > 0
                                && Math.floorMod(dayIndex - 1, interval) == 0;
                        default -> throw reject("SCHEDULE cycle " + cycle + " is not supported");
                    };
                    return outcome(step.operation(), passed,
                            Map.of("cycle", cycle, "interval", Integer.toString(interval)));
                };
            }
            default -> throw reject("operation " + step.operation() + " is not supported");
        };
    }

    private static BasicConditionOutcome withClosedBar(
            BasicInstrumentInput input,
            String resolution,
            String operation,
            java.util.function.Supplier<BasicConditionOutcome> evaluator) {
        if (!Boolean.parseBoolean(input.values().getOrDefault("bar.closed." + resolution, "false"))) {
            return new BasicConditionOutcome(false, "WAITING_FOR_BAR_CLOSE",
                    Map.of("operation", operation, "resolution", resolution));
        }
        return evaluator.get();
    }

    private static BasicConditionOutcome compared(
            String operation,
            ComparisonOperator operator,
            BigDecimal left,
            BigDecimal right,
            Map<String, String> extraEvidence) {
        boolean passed = operator.test(left.compareTo(right));
        Map<String, String> evidence = new LinkedHashMap<>(extraEvidence);
        evidence.put("operator", operator.name());
        evidence.put("left", plain(left));
        evidence.put("right", plain(right));
        return outcome(operation, passed, evidence);
    }

    private static BasicConditionOutcome outcome(
            String operation, boolean passed, Map<String, String> evidence) {
        return new BasicConditionOutcome(passed, operation + (passed ? "_TRUE" : "_FALSE"), evidence);
    }

    private static BigDecimal referencePrice(
            BasicInstrumentInput input,
            String resolution,
            String reference,
            List<BigDecimal> closes) {
        if ("PREVIOUS_CLOSE".equals(reference)) {
            requireSize(closes, 2, "PRICE_REFERENCE");
            return closes.get(closes.size() - 2);
        }
        if ("SESSION_OPEN".equals(reference)) {
            return inputDecimal(input, "session.open", "PRICE_REFERENCE");
        }
        if ("AVERAGE_ENTRY_PRICE".equals(reference)) {
            return inputDecimal(input, "position.averageEntryPrice", "PRICE_REFERENCE");
        }
        String[] pieces = reference.split("_");
        if (pieces.length != 2) {
            throw reject("price reference " + reference + " is not supported");
        }
        int period = Integer.parseInt(pieces[1]);
        return switch (pieces[0]) {
            case "SMA" -> trailingAverage(closes, period, 0);
            case "HIGH" -> extreme(closes, period, 1, true);
            case "LOW" -> extreme(closes, period, 1, false);
            default -> throw reject("price reference " + reference + " is not supported");
        };
    }

    private static List<BigDecimal> series(
            BasicInstrumentInput input, String key, int required, String operation) {
        String raw = input.values().get(key);
        if (raw == null || raw.isBlank()) {
            throw missing(operation, key, required);
        }
        List<BigDecimal> values = Arrays.stream(raw.split(","))
                .filter(value -> !value.isBlank()).map(BigDecimal::new).toList();
        requireSize(values, required, operation);
        return values;
    }

    private static void requireSize(List<?> values, int required, String operation) {
        if (values.size() < required) {
            throw missing(operation, "rollingBars", required);
        }
    }

    private static BasicInputMissingException missing(String operation, String source, int required) {
        return new BasicInputMissingException("FEATURE_WARMUP_INCOMPLETE", Map.of(
                "operation", operation, "source", source,
                "requiredBars", Integer.toString(required)));
    }

    private static BigDecimal inputDecimal(BasicInstrumentInput input, String key, String operation) {
        String raw = input.values().get(key);
        if (raw == null || raw.isBlank()) {
            throw missing(operation, key, 1);
        }
        return new BigDecimal(raw);
    }

    private static BigDecimal decimalArgument(PlanStep step, String name) {
        return new BigDecimal(argument(step, name));
    }

    private static int integerArgument(PlanStep step, String name) {
        return Integer.parseInt(argument(step, name));
    }

    private static BigDecimal average(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), MATH);
    }

    private static BigDecimal trailingAverage(List<BigDecimal> values, int period, int offset) {
        requireSize(values, period + offset, "SMA");
        int end = values.size() - offset;
        return average(values.subList(end - period, end));
    }

    private static BigDecimal extreme(
            List<BigDecimal> values, int period, int offset, boolean maximum) {
        requireSize(values, period + offset, maximum ? "HIGH" : "LOW");
        int end = values.size() - offset;
        return values.subList(end - period, end).stream()
                .reduce(maximum ? BigDecimal::max : BigDecimal::min).orElseThrow();
    }

    private static boolean crossed(
            String direction,
            BigDecimal previousLeft,
            BigDecimal previousRight,
            BigDecimal currentLeft,
            BigDecimal currentRight) {
        return "UP".equals(direction)
                ? previousLeft.compareTo(previousRight) <= 0 && currentLeft.compareTo(currentRight) > 0
                : previousLeft.compareTo(previousRight) >= 0 && currentLeft.compareTo(currentRight) < 0;
    }

    private static BigDecimal percent(BigDecimal numerator, BigDecimal denominator) {
        if (denominator.signum() == 0) {
            throw new IllegalStateException("percentage denominator must not be zero");
        }
        return numerator.multiply(BigDecimal.valueOf(100), MATH).divide(denominator, MATH);
    }

    private static BigDecimal rsi(List<BigDecimal> closes, int period, int offset) {
        int end = closes.size() - offset;
        int start = end - period - 1;
        BigDecimal gains = BigDecimal.ZERO;
        BigDecimal losses = BigDecimal.ZERO;
        for (int index = start + 1; index < end; index++) {
            BigDecimal change = closes.get(index).subtract(closes.get(index - 1));
            if (change.signum() > 0) {
                gains = gains.add(change);
            } else {
                losses = losses.add(change.abs());
            }
        }
        if (losses.signum() == 0) {
            return gains.signum() == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(100);
        }
        BigDecimal relativeStrength = gains.divide(losses, MATH);
        return BigDecimal.valueOf(100).subtract(BigDecimal.valueOf(100)
                .divide(BigDecimal.ONE.add(relativeStrength), MATH));
    }

    private static List<BigDecimal> macdHistogram(
            List<BigDecimal> closes, int fast, int slow, int signal) {
        List<BigDecimal> fastEma = ema(closes, fast);
        List<BigDecimal> slowEma = ema(closes, slow);
        List<BigDecimal> macd = new ArrayList<>();
        for (int index = 0; index < closes.size(); index++) {
            macd.add(fastEma.get(index).subtract(slowEma.get(index), MATH));
        }
        List<BigDecimal> signalLine = ema(macd, signal);
        List<BigDecimal> histogram = new ArrayList<>();
        for (int index = 0; index < macd.size(); index++) {
            histogram.add(macd.get(index).subtract(signalLine.get(index), MATH));
        }
        return histogram;
    }

    private static List<BigDecimal> ema(List<BigDecimal> values, int period) {
        BigDecimal alpha = BigDecimal.valueOf(2)
                .divide(BigDecimal.valueOf(period + 1L), MATH);
        List<BigDecimal> result = new ArrayList<>();
        BigDecimal current = values.getFirst();
        result.add(current);
        for (int index = 1; index < values.size(); index++) {
            current = values.get(index).multiply(alpha, MATH)
                    .add(current.multiply(BigDecimal.ONE.subtract(alpha), MATH), MATH);
            result.add(current);
        }
        return result;
    }

    private static Band band(
            List<BigDecimal> closes, int period, int offset, BigDecimal deviations) {
        int end = closes.size() - offset;
        List<BigDecimal> window = closes.subList(end - period, end);
        BigDecimal mean = average(window);
        BigDecimal variance = window.stream()
                .map(value -> value.subtract(mean).pow(2, MATH))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(period), MATH);
        BigDecimal width = variance.sqrt(MATH).multiply(deviations, MATH);
        return new Band(mean.subtract(width), mean.add(width));
    }

    private static BigDecimal last(List<BigDecimal> values) {
        return values.getLast();
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private record Band(BigDecimal lower, BigDecimal upper) {}

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
