package com.idea2strategy.trading.worker.runtime;

import com.idea2strategy.trading.application.candidate.CandidateBatchProcessingResult;
import com.idea2strategy.trading.application.candidate.CandidateBatchProcessor;
import com.idea2strategy.trading.messaging.evaluation.OrderCandidate;
import com.idea2strategy.trading.messaging.evaluation.OrderCandidateBatch;
import com.idea2strategy.trading.messaging.evaluation.OrderSide;
import com.idea2strategy.trading.messaging.market.MarketEventEnvelope;
import com.idea2strategy.trading.messaging.market.MarketEventType;
import com.idea2strategy.trading.strategy.runtime.basic.BasicDecisionStatus;
import com.idea2strategy.trading.strategy.runtime.basic.BasicExecutionRequest;
import com.idea2strategy.trading.strategy.runtime.basic.BasicExecutionResult;
import com.idea2strategy.trading.strategy.runtime.basic.BasicInstrumentInput;
import com.idea2strategy.trading.strategy.runtime.basic.BasicOrderSide;
import com.idea2strategy.trading.strategy.runtime.basic.BasicStrategyExecutor;
import com.idea2strategy.trading.strategy.runtime.candidate.BasicCandidateConvergenceResult;
import com.idea2strategy.trading.strategy.runtime.candidate.BasicCandidateConverger;
import com.idea2strategy.trading.strategy.runtime.candidate.BasicOrderCandidate;
import com.idea2strategy.trading.strategy.runtime.control.BotRuntimeLifecycle;
import com.idea2strategy.trading.strategy.runtime.control.EvaluationWindow;
import com.idea2strategy.trading.strategy.runtime.feature.BoundedWindowFeatureCalculator;
import com.idea2strategy.trading.strategy.runtime.feature.OfficialFeatureCatalog;
import com.idea2strategy.trading.strategy.runtime.incremental.IncrementalFeatureSnapshot;
import com.idea2strategy.trading.strategy.runtime.incremental.IncrementalFeatureState;
import com.idea2strategy.trading.strategy.runtime.incremental.OrderedIncrementalFeatureRuntime;
import com.idea2strategy.trading.strategy.runtime.incremental.RuntimeTrigger;
import com.idea2strategy.trading.strategy.runtime.incremental.RuntimeTriggerType;
import com.idea2strategy.trading.strategy.runtime.plan.BasicPlanInterpreter;
import com.idea2strategy.trading.strategy.runtime.plan.LoadedExecutionPlan;
import com.idea2strategy.trading.strategy.runtime.warmup.FeatureObservation;
import com.idea2strategy.trading.strategy.runtime.warmup.PreparedWarmup;
import com.idea2strategy.trading.strategy.runtime.warmup.WarmupFeatureSeries;
import com.idea2strategy.trading.worker.candidate.OrderCandidateBatchAdapter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RT2: the evaluation loop that turns B's running bots and C's market events into order candidates.
 *
 * <p>Every part of this path was merged and none of it was connected. The control consumer resolved a
 * bot's locked plan, the startup gate prepared its warm-up, the incremental runtime, the Basic
 * executor and the converger could each do their step, and the candidate processor could turn a batch
 * into canonical orders — but no production {@link BotRuntimeLifecycle} existed, so a started bot
 * evaluated nothing.
 *
 * <p><strong>Start</strong> interprets the locked plan into flows, seeds each feature's window from
 * the prepared warm-up so the bot can decide on its first live event rather than fifteen bars later,
 * and registers the bot. <strong>Feed</strong> routes an event to the bots subscribed to its
 * instrument, advances features in event order, evaluates, converges, and hands the resulting batch to
 * {@link CandidateBatchProcessor} in-process — the same claim boundary F90 proved exactly-once.
 * <strong>Stop</strong> unregisters; the durable settlement belongs to the F91 bridge that wraps this.
 *
 * <p>The batch is published on the version 3 contract, so a buy carries the share C decided and a sell
 * carries no measure at all. That is what root #197 settled: the runtime knows the share, and only the
 * composer holds the spendable cash, price, fee and buffer needed to turn it into shares. It travels
 * through {@link OrderCandidateBatchAdapter} rather than straight into the domain, so an in-process
 * batch is validated exactly as a queued one is.
 *
 * <p><strong>A redelivered event converges.</strong> The evaluation and batch identities are derived
 * from the market event, so re-feeding it produces the same batch id, which the processor's claim
 * ledger then reports as a duplicate rather than processing twice. Feeding is serialised per bot,
 * which is C13's rule: one bot evaluates one thing at a time.
 */
public final class EvaluatingBotRuntime implements BotRuntimeLifecycle {

    private static final Logger log = LoggerFactory.getLogger(EvaluatingBotRuntime.class);

    /** The trigger value a market event's price lands on for the feature calculators. */
    private static final String CLOSE = BoundedWindowFeatureCalculator.CLOSE_TRIGGER_VALUE;

    private final CandidateBatchProcessor processor;
    private final OrderCandidateBatchAdapter adapter;
    private final BotScopeResolver scopeResolver;
    private final EvaluationRunRecorder runRecorder;
    private final PositionMetricSource positionMetricSource;
    private final ExecutionGateStateSource executionGateStateSource;
    private final BasicPlanInterpreter interpreter = new BasicPlanInterpreter();
    private final BasicStrategyExecutor executor = new BasicStrategyExecutor();
    private final BasicCandidateConverger converger = new BasicCandidateConverger();
    private final Map<UUID, RegisteredBot> bots = new ConcurrentHashMap<>();

    public EvaluatingBotRuntime(
            CandidateBatchProcessor processor,
            OrderCandidateBatchAdapter adapter,
            BotScopeResolver scopeResolver,
            EvaluationRunRecorder runRecorder) {
        this(processor, adapter, scopeResolver, runRecorder,
                PositionMetricSource.none(), ExecutionGateStateSource.none());
    }

    public EvaluatingBotRuntime(
            CandidateBatchProcessor processor,
            OrderCandidateBatchAdapter adapter,
            BotScopeResolver scopeResolver,
            EvaluationRunRecorder runRecorder,
            PositionMetricSource positionMetricSource) {
        this(processor, adapter, scopeResolver, runRecorder,
                positionMetricSource, ExecutionGateStateSource.none());
    }

    public EvaluatingBotRuntime(
            CandidateBatchProcessor processor,
            OrderCandidateBatchAdapter adapter,
            BotScopeResolver scopeResolver,
            EvaluationRunRecorder runRecorder,
            PositionMetricSource positionMetricSource,
            ExecutionGateStateSource executionGateStateSource) {
        this.processor = Objects.requireNonNull(processor, "processor");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.scopeResolver = Objects.requireNonNull(scopeResolver, "scopeResolver");
        this.runRecorder = Objects.requireNonNull(runRecorder, "runRecorder");
        this.positionMetricSource = Objects.requireNonNull(positionMetricSource, "positionMetricSource");
        this.executionGateStateSource =
                Objects.requireNonNull(executionGateStateSource, "executionGateStateSource");
    }

    @Override
    public void start(LoadedExecutionPlan plan, PreparedWarmup warmup, EvaluationWindow window) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(window, "window");
        var interpreted = interpreter.interpret(plan.planPayload());
        var evaluationTimeframe = StrategyEvaluationTimeframe.fromPlan(plan.planPayload());
        var calculator = new BoundedWindowFeatureCalculator(OfficialFeatureCatalog.RSI_14);
        Map<UUID, InstrumentRuntimeState> instrumentStates = new LinkedHashMap<>();
        for (UUID instrumentId : interpreted.subscribedInstruments()) {
            instrumentStates.put(instrumentId, new InstrumentRuntimeState(
                    new OrderedIncrementalFeatureRuntime(
                            plan.botId(), -1, List.of(calculator),
                            Map.of(calculator.key(), seedFrom(warmup, calculator, instrumentId)))));
        }

        bots.put(plan.botId(), new RegisteredBot(
                plan.botId(), interpreted, instrumentStates, calculator, evaluationTimeframe, window));
        log.info("bot {} registered for evaluation over {} instruments within {}",
                plan.botId(), interpreted.subscribedInstruments().size(), window);
    }

    @Override
    public void stop(UUID botId, String reasonCode) {
        Objects.requireNonNull(botId, "botId");
        if (bots.remove(botId) != null) {
            log.info("bot {} unregistered from evaluation: {}", botId, reasonCode);
        }
    }

    /** True while this runtime is evaluating the bot. */
    public boolean isEvaluating(UUID botId) {
        return bots.containsKey(Objects.requireNonNull(botId, "botId"));
    }

    /**
     * Feeds one normalised market event to every bot subscribed to its instrument.
     *
     * <p>Returns one result per batch actually produced. An event no registered bot subscribes to,
     * one arriving before a bot's eligibility instant, or one producing no candidate yields nothing —
     * never an empty batch, which the canonical intent tables would have no reason to hold.
     */
    public List<CandidateBatchProcessingResult> feed(MarketEventEnvelope event) {
        Objects.requireNonNull(event, "event");
        if (event.eventType() != MarketEventType.MARKET_EVALUATION_READY) {
            log.debug("ignoring non-evaluation market event {} ({})", event.eventId(), event.eventType());
            return List.of();
        }
        List<CandidateBatchProcessingResult> results = new ArrayList<>();
        for (RegisteredBot bot : bots.values()) {
            if (!bot.plan().subscribedInstruments().contains(event.instrumentId())) {
                continue;
            }
            if (!closesAnyRequiredTimeframe(event, bot.evaluationTimeframes())) {
                continue;
            }
            if (!bot.window().admits(event.occurredAt())) {
                // A room bot waits for its evaluation window to open and stops deciding the moment it
                // closes. Refusing here rather than relying on the stop arriving punctually is what
                // keeps a trade decided after the room stopped counting out of the shared canonical
                // ledger the room's performance is read from (C93).
                continue;
            }
            evaluate(bot, event).ifPresent(results::add);
        }
        return List.copyOf(results);
    }

    /**
     * Advances subscribed bots over an old stream entry without producing an order.
     *
     * <p>This is the recovery path for a consumer group that is beyond its safe decision lag. The
     * worker must drain that backlog or it can never become current, but deciding on every stale bar
     * would turn recovery into a burst of obsolete orders. Catch-up therefore keeps indicators,
     * rolling bars and position-age metrics current, then normal {@link #feed(MarketEventEnvelope)}
     * resumes once the stream is inside the configured lag window.
     */
    public void catchUp(MarketEventEnvelope event) {
        Objects.requireNonNull(event, "event");
        if (event.eventType() != MarketEventType.MARKET_EVALUATION_READY) {
            return;
        }
        for (RegisteredBot bot : bots.values()) {
            if (!bot.plan().subscribedInstruments().contains(event.instrumentId())
                    || !closesAnyRequiredTimeframe(event, bot.evaluationTimeframes())
                    || !bot.window().admits(event.occurredAt())) {
                continue;
            }
            synchronized (bot) {
                advance(bot, event).ifPresent(advanced ->
                        inputsFor(bot, event, advanced.snapshot(), advanced.price(), advanced.marketValues()));
            }
        }
    }

    /** One bot's evaluation of one event, serialised on the bot as C13 requires. */
    private Optional<CandidateBatchProcessingResult> evaluate(
            RegisteredBot bot, MarketEventEnvelope event) {
        synchronized (bot) {
            Optional<AdvancedMarketState> advancedState = advance(bot, event);
            if (advancedState.isEmpty()) {
                return Optional.empty();
            }
            AdvancedMarketState advanced = advancedState.orElseThrow();
            if (!closesAllRequiredTimeframes(event, bot.evaluationTimeframes())) {
                return Optional.empty();
            }
            BigDecimal price = advanced.price();
            IncrementalFeatureSnapshot snapshot = advanced.snapshot();
            Map<String, String> marketValues = advanced.marketValues();

            UUID evaluationId = derived("evaluation", bot.botId() + ":" + event.eventId());
            BasicExecutionResult execution = executor.execute(new BasicExecutionRequest(
                    evaluationId, bot.plan().flows(),
                    inputsFor(bot, event, snapshot, price, marketValues)));
            BasicCandidateConvergenceResult converged =
                    converger.converge(evaluationId, acceptedOf(bot, execution, evaluationId, event));
            if (converged.acceptedCandidates().isEmpty()) {
                return Optional.empty();
            }

            // Every accepted candidate of one evaluation belongs to one partition: the partition of
            // one official bot event is the trading isolation boundary the canonical batch is keyed by.
            String flowKey = converged.acceptedCandidates().getFirst().flowId();
            Optional<BotScope> scope = scopeResolver.resolve(bot.botId(), flowKey);
            if (scope.isEmpty()) {
                // The bot's own canonical rows are missing, so no intent could be written. Failing
                // here would drop the event for every other bot; leaving it unevaluated keeps the
                // market stream moving while the gap stays visible.
                log.warn("bot {} has no canonical scope for flow {}; event {} not evaluated",
                        bot.botId(), flowKey, event.eventId());
                return Optional.empty();
            }

            UUID sourceEventId = runRecorder.recordEvaluationRun(
                    bot.botId(), scope.get(), evaluationId, event);
            return Optional.of(processor.process(adapter.toDomain(
                    batchOf(bot, scope.get(), converged, event, evaluationId, sourceEventId, price))));
        }
    }

    /** Advances only the state belonging to this event's instrument. Caller holds the bot monitor. */
    private Optional<AdvancedMarketState> advance(RegisteredBot bot, MarketEventEnvelope event) {
        BigDecimal price = priceOf(event);
        if (price == null) {
            return Optional.empty();
        }
        InstrumentRuntimeState state = bot.instrumentState(event.instrumentId());
        if (!state.marketSequenceAdvances(event.sequence())) {
            log.debug("bot {} ignoring market event {} for instrument {} at sequence {}, already past {}",
                    bot.botId(), event.eventId(), event.instrumentId(), event.sequence(),
                    state.lastMarketSequence());
            return Optional.empty();
        }
        IncrementalFeatureSnapshot snapshot = state.features().process(new RuntimeTrigger(
                bot.botId(), state.nextLocalSequence(), event.eventId(), RuntimeTriggerType.MARKET,
                event.occurredAt(), Map.of(CLOSE, price)));
        Map<String, String> marketValues = bot.signalState(event.instrumentId()).accept(event);
        return Optional.of(new AdvancedMarketState(snapshot, price, marketValues));
    }

    /**
     * The executor's per-instrument inputs.
     *
     * <p>Only the instrument the event moved carries a fresh feature value. The plan's other
     * instruments are supplied with no feature at all rather than a neighbour's number, so the
     * executor decides INPUT_MISSING for them — which is the same decision D's runtime reaches for an
     * instrument whose series has not advanced.
     */
    private Map<UUID, BasicInstrumentInput> inputsFor(
            RegisteredBot bot,
            MarketEventEnvelope event,
            IncrementalFeatureSnapshot snapshot,
            BigDecimal price,
            Map<String, String> marketValues) {
        Map<String, String> values = new LinkedHashMap<>(marketValues);
        values.put("price", price.toPlainString());
        IncrementalFeatureState state = snapshot.featureStates().get(bot.calculator().key());
        if (state != null) {
            bot.calculator().valueOf(state).ifPresent(value ->
                    values.put(bot.calculator().feature().featureId(), value.toPlainString()));
        }
        positionMetricSource.resolve(bot.botId(), event.instrumentId()).ifPresentOrElse(
                position -> bot.positionTracker(event.instrumentId(), position)
                        .publish(values, position, price, event.occurredAt(), marketValues),
                () -> bot.clearPositionTracker(event.instrumentId()));

        Map<UUID, BasicInstrumentInput> inputs = new LinkedHashMap<>();
        for (UUID instrumentId : bot.plan().subscribedInstruments()) {
            inputs.put(instrumentId, instrumentId.equals(event.instrumentId())
                    ? new BasicInstrumentInput(instrumentId, Map.copyOf(values))
                    : new BasicInstrumentInput(instrumentId, Map.of()));
        }
        return inputs;
    }

    private List<BasicOrderCandidate> acceptedOf(
            RegisteredBot bot,
            BasicExecutionResult execution,
            UUID evaluationId,
            MarketEventEnvelope event) {
        List<BasicOrderCandidate> candidates = new ArrayList<>();
        execution.decisions().stream()
                .filter(decision -> decision.instrumentId().equals(event.instrumentId()))
                .forEach(decision -> {
                    BasicPlanInterpreter.ExecutionPolicy policy =
                            bot.plan().executionPolicyByFlowKey().get(decision.flowId());
                    if (!bot.executionGate(
                                    decision.flowId(),
                                    decision.instrumentId(),
                                    () -> executionGateStateSource.resolve(
                                            bot.botId(), decision.flowId(), decision.instrumentId()))
                            .accepts(decision.status(), policy, event.occurredAt())) {
                        return;
                    }
                    candidates.add(new BasicOrderCandidate(
                        derived("candidate",
                                evaluationId + ":" + decision.flowId() + ":" + decision.instrumentId()),
                        decision.flowId(),
                        decision.instrumentId(),
                        decision.side(),
                        decision.buyAllocation(),
                        Map.of(
                                "evaluationId", evaluationId.toString(),
                                "orderPercent", Integer.toString(policy.orderPercent()))));
                });
        return candidates;
    }

    /**
     * The version 3 batch this evaluation publishes.
     *
     * <p>A buy hands over the share the executor decided; a sell hands over nothing, because its size
     * is the position held and only the composer's lots know it. Stamped with the event that caused
     * it rather than a clock, so a redelivery composes the identical batch instead of a new one.
     */
    private OrderCandidateBatch batchOf(
            RegisteredBot bot,
            BotScope scope,
            BasicCandidateConvergenceResult converged,
            MarketEventEnvelope event,
            UUID evaluationId,
            UUID sourceEventId,
            BigDecimal referencePrice) {
        List<OrderCandidate> candidates = new ArrayList<>();
        for (BasicOrderCandidate candidate : converged.acceptedCandidates()) {
            int orderPercent = Integer.parseInt(candidate.actionParameters().get("orderPercent"));
            candidates.add(candidate.side() == BasicOrderSide.BUY
                    ? OrderCandidate.allocatedBuy(
                            candidate.candidateId(), candidate.instrumentId(), scope.flowId(),
                            candidate.buyAllocation().orElseThrow().numerator() * orderPercent,
                            candidate.buyAllocation().orElseThrow().denominator() * 100,
                            referencePrice, null, List.of("BASIC_RULE_MATCHED"))
                    : OrderCandidate.partialHeldSell(
                            candidate.candidateId(), candidate.instrumentId(), scope.flowId(),
                            orderPercent, referencePrice, null, List.of("BASIC_RULE_MATCHED")));
        }
        return new OrderCandidateBatch(
                OrderCandidateBatch.PARTIAL_POSITION_SCHEMA_VERSION,
                derived("candidate-batch", bot.botId() + ":" + event.eventId()),
                evaluationId,
                bot.botId(),
                scope.partitionId(),
                sourceEventId,
                event.occurredAt(),
                candidates);
    }

    /**
     * Seeds a feature's window from the warm-up the startup gate resolved.
     *
     * <p>Without this a bounded-window feature holds nothing and reports warm-up incomplete for its
     * first fifteen bars of live data, even though the gate had already fetched exactly the history
     * needed. Only series naming this build's feature are replayed; anything else is another
     * definition's data and would corrupt the window.
     */
    private static IncrementalFeatureState seedFrom(
            PreparedWarmup warmup,
            BoundedWindowFeatureCalculator calculator,
            UUID instrumentId) {
        IncrementalFeatureState seeded = new IncrementalFeatureState(0, Map.of());
        if (warmup == null) {
            return seeded;
        }
        for (WarmupFeatureSeries series : warmup.seriesByRequirementId().values()) {
            if (!calculator.feature().featureId().equals(series.featureId())
                    && !calculator.key().featureId().equals(series.featureId())) {
                continue;
            }
            for (FeatureObservation observation : series.observations()) {
                if (!instrumentId.toString().equals(observation.instrument())) {
                    continue;
                }
                seeded = calculator.calculate(seeded, new RuntimeTrigger(
                        derived("warmup", series.requirementId()),
                        0,
                        "warmup:" + series.requirementId() + ":" + observation.observedAt(),
                        RuntimeTriggerType.MARKET,
                        observation.observedAt(),
                        Map.of(CLOSE, observation.value())));
            }
        }
        return seeded;
    }

    /** The price a market event contributes to a bounded-window feature. */
    private static BigDecimal priceOf(MarketEventEnvelope event) {
        BigDecimal close = event.values().get("close");
        return close != null ? close : event.values().get("price");
    }

    private static boolean closesAnyRequiredTimeframe(
            MarketEventEnvelope event, Set<StrategyEvaluationTimeframe> timeframes) {
        return timeframes.stream().anyMatch(timeframe -> closesTimeframe(event, timeframe));
    }

    private static boolean closesAllRequiredTimeframes(
            MarketEventEnvelope event, Set<StrategyEvaluationTimeframe> timeframes) {
        return timeframes.stream().allMatch(timeframe -> closesTimeframe(event, timeframe));
    }

    private static boolean closesTimeframe(
            MarketEventEnvelope event, StrategyEvaluationTimeframe timeframe) {
        BigDecimal flag = event.values().get(timeframe.closedFlag());
        if (flag != null) {
            return flag.signum() > 0;
        }
        return event.schemaVersion() == 1 && timeframe == StrategyEvaluationTimeframe.THIRTY_MINUTES;
    }

    private static UUID derived(String kind, String material) {
        return UUID.nameUUIDFromBytes((kind + ":" + material).getBytes(StandardCharsets.UTF_8));
    }

    private record AdvancedMarketState(
            IncrementalFeatureSnapshot snapshot,
            BigDecimal price,
            Map<String, String> marketValues) {}

    /**
     * One registered bot and the two orderings it tracks.
     *
     * <p>Not a record because both counters advance as events arrive. Every read and write happens
     * inside {@code synchronized (bot)} in {@link #evaluate}, which is also C13's rule that one bot
     * evaluates one thing at a time, so the mutation needs no further guard.
     */
    private static final class RegisteredBot {
        private final UUID botId;
        private final BasicPlanInterpreter.InterpretedPlan plan;
        private final Map<UUID, InstrumentRuntimeState> instrumentStates;
        private final BoundedWindowFeatureCalculator calculator;
        private final Set<StrategyEvaluationTimeframe> evaluationTimeframes;
        private final EvaluationWindow window;
        private final Map<UUID, BasicMarketSignalState> signalStates = new LinkedHashMap<>();
        private final Map<UUID, PositionTracker> positionTrackers = new LinkedHashMap<>();
        private final Map<String, ExecutionGate> executionGates = new LinkedHashMap<>();

        private RegisteredBot(
                UUID botId,
                BasicPlanInterpreter.InterpretedPlan plan,
                Map<UUID, InstrumentRuntimeState> instrumentStates,
                BoundedWindowFeatureCalculator calculator,
                Set<StrategyEvaluationTimeframe> evaluationTimeframes,
                EvaluationWindow window) {
            this.botId = botId;
            this.plan = plan;
            this.instrumentStates = new LinkedHashMap<>(instrumentStates);
            this.calculator = calculator;
            this.evaluationTimeframes = Set.copyOf(evaluationTimeframes);
            this.window = window;
        }

        private UUID botId() {
            return botId;
        }

        private BasicPlanInterpreter.InterpretedPlan plan() {
            return plan;
        }

        private InstrumentRuntimeState instrumentState(UUID instrumentId) {
            InstrumentRuntimeState state = instrumentStates.get(instrumentId);
            if (state == null) {
                throw new IllegalArgumentException("instrument is not subscribed by bot: " + instrumentId);
            }
            return state;
        }

        private BoundedWindowFeatureCalculator calculator() {
            return calculator;
        }

        private Set<StrategyEvaluationTimeframe> evaluationTimeframes() {
            return evaluationTimeframes;
        }

        private EvaluationWindow window() {
            return window;
        }

        private BasicMarketSignalState signalState(UUID instrumentId) {
            return signalStates.computeIfAbsent(instrumentId, ignored -> new BasicMarketSignalState());
        }

        private PositionTracker positionTracker(UUID instrumentId, PositionSnapshot snapshot) {
            PositionTracker current = positionTrackers.get(instrumentId);
            if (current != null && current.matches(snapshot)) {
                return current;
            }
            clearExecutionGates(instrumentId);
            PositionTracker replacement = new PositionTracker(snapshot);
            positionTrackers.put(instrumentId, replacement);
            return replacement;
        }

        private void clearPositionTracker(UUID instrumentId) {
            if (positionTrackers.remove(instrumentId) != null) {
                clearExecutionGates(instrumentId);
            }
        }

        private ExecutionGate executionGate(
                String flowId, UUID instrumentId, Supplier<ExecutionGateSnapshot> snapshot) {
            return executionGates.computeIfAbsent(
                    flowId + ":" + instrumentId, ignored -> new ExecutionGate(snapshot.get()));
        }

        private void clearExecutionGates(UUID instrumentId) {
            String suffix = ":" + instrumentId;
            executionGates.keySet().removeIf(key -> key.endsWith(suffix));
        }
    }

    static final class ExecutionGate {
        private int executions;
        private int barsSinceExecution;
        private Instant lastExecutionAt;
        private boolean conditionRearmed = true;

        ExecutionGate() {
            this(ExecutionGateSnapshot.empty());
        }

        ExecutionGate(ExecutionGateSnapshot snapshot) {
            Objects.requireNonNull(snapshot, "snapshot");
            executions = snapshot.executions();
            lastExecutionAt = snapshot.lastExecutionAt();
            conditionRearmed = executions == 0;
        }

        boolean accepts(
                BasicDecisionStatus status,
                BasicPlanInterpreter.ExecutionPolicy policy,
                Instant occurredAt) {
            if (lastExecutionAt != null) {
                barsSinceExecution++;
            }
            if (status != BasicDecisionStatus.CANDIDATE) {
                if (status == BasicDecisionStatus.CONDITION_NOT_MET) {
                    conditionRearmed = true;
                }
                return false;
            }
            int limit = policy.executionMode().equals("1회만")
                    ? 1
                    : policy.maxExecutions();
            if (executions >= limit) {
                return false;
            }
            boolean eligible = executions == 0
                    || policy.executionMode().equals("주기마다")
                    || switch (policy.waitMode()) {
                        case "조건 재충족" -> conditionRearmed;
                        case "N봉 이후" -> barsSinceExecution >= policy.waitInterval();
                        case "N거래일 이후" -> tradingDaysSince(lastExecutionAt, occurredAt)
                                >= policy.waitInterval();
                        default -> false;
                    };
            if (!eligible) {
                return false;
            }
            executions++;
            barsSinceExecution = 0;
            lastExecutionAt = occurredAt;
            conditionRearmed = false;
            return true;
        }

        private static long tradingDaysSince(Instant start, Instant end) {
            LocalDate cursor = start.atZone(ZoneId.of("America/New_York")).toLocalDate();
            LocalDate through = end.atZone(ZoneId.of("America/New_York")).toLocalDate();
            long days = 0;
            while (cursor.isBefore(through)) {
                cursor = cursor.plusDays(1);
                if (cursor.getDayOfWeek().getValue() <= 5) {
                    days++;
                }
            }
            return days;
        }
    }

    /** Mutable market and feature ordering for exactly one bot/instrument pair. */
    private static final class InstrumentRuntimeState {
        private final OrderedIncrementalFeatureRuntime features;
        private long lastMarketSequence = Long.MIN_VALUE;
        private long localSequence = -1;

        private InstrumentRuntimeState(OrderedIncrementalFeatureRuntime features) {
            this.features = Objects.requireNonNull(features, "features");
        }

        private boolean marketSequenceAdvances(long sequence) {
            if (sequence <= lastMarketSequence) {
                return false;
            }
            lastMarketSequence = sequence;
            return true;
        }

        private long nextLocalSequence() {
            return ++localSequence;
        }

        private long lastMarketSequence() {
            return lastMarketSequence;
        }

        private OrderedIncrementalFeatureRuntime features() {
            return features;
        }
    }

    static final class PositionTracker {
        private static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");
        private final BigDecimal averageEntryPrice;
        private final Instant openedAt;
        private BigDecimal peakPrice;
        private final Map<String, Long> closedBars = new LinkedHashMap<>();

        PositionTracker(PositionSnapshot snapshot) {
            this.averageEntryPrice = snapshot.averageEntryPrice();
            this.openedAt = snapshot.openedAt();
            this.peakPrice = averageEntryPrice;
        }

        private boolean matches(PositionSnapshot snapshot) {
            return averageEntryPrice.compareTo(snapshot.averageEntryPrice()) == 0
                    && openedAt.equals(snapshot.openedAt());
        }

        void publish(
                Map<String, String> values,
                PositionSnapshot snapshot,
                BigDecimal price,
                Instant occurredAt,
                Map<String, String> marketValues) {
            peakPrice = peakPrice.max(price);
            BigDecimal currentReturn = percentage(price.subtract(averageEntryPrice), averageEntryPrice);
            BigDecimal peakReturn = percentage(peakPrice.subtract(averageEntryPrice), averageEntryPrice);
            BigDecimal drawdown = percentage(peakPrice.subtract(price), peakPrice);
            values.put("position.averageEntryPrice", averageEntryPrice.toPlainString());
            values.put("position.returnPercent", currentReturn.toPlainString());
            values.put("position.peakReturnPercent", peakReturn.toPlainString());
            values.put("position.drawdownPercent", drawdown.toPlainString());
            for (String resolution : List.of("30m", "1h", "4h", "1d")) {
                if (Boolean.parseBoolean(marketValues.getOrDefault("bar.closed." + resolution, "false"))) {
                    closedBars.merge(resolution, 1L, Long::sum);
                }
                values.put("position.holdingBars." + resolution,
                        Long.toString(closedBars.getOrDefault(resolution, 0L)));
            }
            LocalDate opened = openedAt.atZone(MARKET_ZONE).toLocalDate();
            LocalDate current = occurredAt.atZone(MARKET_ZONE).toLocalDate();
            values.put("position.holdingTradingDays",
                    Long.toString(tradingWeekdaysBetween(opened, current)));
        }

        /**
         * A published position metric, under {@code precision:1.0.0}: 8 fractional digits,
         * HALF_EVEN.
         *
         * <p>HALF_UP here was the wrong half of a pair. These values are what a
         * {@code POSITION_RETURN} step compares against a threshold, and the backtest quantizes
         * them HALF_EVEN as the precision rules require, so an exact tie at the ninth decimal
         * produced two different published numbers for one position — and the rendered form
         * reaches the step trace, so it produced two different traces as well.
         */
        private static BigDecimal percentage(BigDecimal numerator, BigDecimal denominator) {
            if (denominator.signum() == 0) {
                return BigDecimal.ZERO;
            }
            return numerator.multiply(BigDecimal.valueOf(100))
                    .divide(denominator, 8, java.math.RoundingMode.HALF_EVEN);
        }

        private static long tradingWeekdaysBetween(LocalDate start, LocalDate end) {
            long count = 0;
            for (long day = 0; day <= Math.max(0, ChronoUnit.DAYS.between(start, end)); day++) {
                java.time.DayOfWeek weekday = start.plusDays(day).getDayOfWeek();
                if (weekday != java.time.DayOfWeek.SATURDAY
                        && weekday != java.time.DayOfWeek.SUNDAY) {
                    count++;
                }
            }
            return Math.max(0, count - 1);
        }
    }

    /** The canonical ids of one flow, which a candidate batch cannot be written without. */
    public record BotScope(UUID partitionId, UUID flowId) {
        public BotScope {
            Objects.requireNonNull(partitionId, "partitionId");
            Objects.requireNonNull(flowId, "flowId");
        }
    }

    /**
     * Resolves a plan's flow key to the canonical ids B's provisioning wrote.
     *
     * <p>Keyed on the flow alone, not the partition: B writes the flow's key as {@code bot.flows.name}
     * ({@code derivedId(releaseId, "flow:" + key), key}) while a partition's name is the strategy's
     * display name, not the plan's partition key. The flow's row carries its partition, so resolving
     * the flow resolves both.
     */
    public interface BotScopeResolver {
        Optional<BotScope> resolve(UUID botId, String flowKey);
    }

    /**
     * Records the {@code bot.evaluation_runs} row an intent batch's foreign key requires, and returns
     * the official {@code bot.bot_events} id the batch is sourced from.
     */
    public interface EvaluationRunRecorder {
        UUID recordEvaluationRun(
                UUID botId, BotScope scope, UUID evaluationId, MarketEventEnvelope event);
    }

    /** Reads the durable current position used by position-dependent sell blocks. */
    public interface PositionMetricSource {
        Optional<PositionSnapshot> resolve(UUID botId, UUID instrumentId);

        static PositionMetricSource none() {
            return (botId, instrumentId) -> Optional.empty();
        }
    }

    /** Restores a flow's execution limiter from canonical intents in the current position cycle. */
    public interface ExecutionGateStateSource {
        ExecutionGateSnapshot resolve(UUID botId, String flowKey, UUID instrumentId);

        static ExecutionGateStateSource none() {
            return (botId, flowKey, instrumentId) -> ExecutionGateSnapshot.empty();
        }
    }

    public record ExecutionGateSnapshot(int executions, Instant lastExecutionAt) {
        public ExecutionGateSnapshot {
            if (executions < 0) {
                throw new IllegalArgumentException("executions must not be negative");
            }
            if ((executions == 0) != (lastExecutionAt == null)) {
                throw new IllegalArgumentException(
                        "lastExecutionAt must exist exactly when executions are present");
            }
        }

        static ExecutionGateSnapshot empty() {
            return new ExecutionGateSnapshot(0, null);
        }
    }

    public record PositionSnapshot(BigDecimal averageEntryPrice, Instant openedAt) {
        public PositionSnapshot {
            Objects.requireNonNull(averageEntryPrice, "averageEntryPrice");
            Objects.requireNonNull(openedAt, "openedAt");
            if (averageEntryPrice.signum() <= 0) {
                throw new IllegalArgumentException("averageEntryPrice must be positive");
            }
        }
    }
}
