package com.idea2strategy.trading.worker.runtime;

import com.idea2strategy.trading.application.candidate.CandidateBatchProcessingResult;
import com.idea2strategy.trading.application.candidate.CandidateBatchProcessor;
import com.idea2strategy.trading.messaging.evaluation.OrderCandidate;
import com.idea2strategy.trading.messaging.evaluation.OrderCandidateBatch;
import com.idea2strategy.trading.messaging.evaluation.OrderSide;
import com.idea2strategy.trading.messaging.market.MarketEventEnvelope;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    private final BasicPlanInterpreter interpreter = new BasicPlanInterpreter();
    private final BasicStrategyExecutor executor = new BasicStrategyExecutor();
    private final BasicCandidateConverger converger = new BasicCandidateConverger();
    private final Map<UUID, RegisteredBot> bots = new ConcurrentHashMap<>();

    public EvaluatingBotRuntime(
            CandidateBatchProcessor processor,
            OrderCandidateBatchAdapter adapter,
            BotScopeResolver scopeResolver,
            EvaluationRunRecorder runRecorder) {
        this.processor = Objects.requireNonNull(processor, "processor");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.scopeResolver = Objects.requireNonNull(scopeResolver, "scopeResolver");
        this.runRecorder = Objects.requireNonNull(runRecorder, "runRecorder");
    }

    @Override
    public void start(LoadedExecutionPlan plan, PreparedWarmup warmup, EvaluationWindow window) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(window, "window");
        var interpreted = interpreter.interpret(plan.planPayload());
        var calculator = new BoundedWindowFeatureCalculator(OfficialFeatureCatalog.RSI_14);
        var features = new OrderedIncrementalFeatureRuntime(
                plan.botId(), -1, List.of(calculator),
                Map.of(calculator.key(), seedFrom(warmup, calculator)));

        bots.put(plan.botId(), new RegisteredBot(
                plan.botId(), interpreted, features, calculator, window));
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
        List<CandidateBatchProcessingResult> results = new ArrayList<>();
        for (RegisteredBot bot : bots.values()) {
            if (!bot.plan().subscribedInstruments().contains(event.instrumentId())) {
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

    /** One bot's evaluation of one event, serialised on the bot as C13 requires. */
    private Optional<CandidateBatchProcessingResult> evaluate(
            RegisteredBot bot, MarketEventEnvelope event) {
        synchronized (bot) {
            BigDecimal price = priceOf(event);
            if (price == null) {
                return Optional.empty();
            }
            if (!bot.marketSequenceAdvances(event.sequence())) {
                // A repeat or a late arrival. Evaluating it would decide from data the bot has already
                // moved past, and the decision would look current. The processor's claim ledger is
                // still the durable guarantee; this only avoids doing the work twice.
                log.debug("bot {} ignoring market event {} at sequence {}, already past {}",
                        bot.botId(), event.eventId(), event.sequence(), bot.lastMarketSequence());
                return Optional.empty();
            }
            // The feature runtime's sequence is its own dense ordering, not the market's: a bot that
            // starts mid-stream sees whatever sequence the gateway is on, and the runtime rejects a
            // gap. The market's own ordering is checked above, where it means something.
            IncrementalFeatureSnapshot snapshot = bot.features().process(new RuntimeTrigger(
                    bot.botId(), bot.nextLocalSequence(), event.eventId(), RuntimeTriggerType.MARKET,
                    event.occurredAt(), Map.of(CLOSE, price)));

            UUID evaluationId = derived("evaluation", bot.botId() + ":" + event.eventId());
            BasicExecutionResult execution = executor.execute(new BasicExecutionRequest(
                    evaluationId, bot.plan().flows(), inputsFor(bot, event, snapshot, price)));
            BasicCandidateConvergenceResult converged =
                    converger.converge(evaluationId, acceptedOf(execution, evaluationId));
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
            BigDecimal price) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("price", price.toPlainString());
        IncrementalFeatureState state = snapshot.featureStates().get(bot.calculator().key());
        if (state != null) {
            bot.calculator().valueOf(state).ifPresent(value ->
                    values.put(bot.calculator().feature().featureId(), value.toPlainString()));
        }

        Map<UUID, BasicInstrumentInput> inputs = new LinkedHashMap<>();
        for (UUID instrumentId : bot.plan().subscribedInstruments()) {
            inputs.put(instrumentId, instrumentId.equals(event.instrumentId())
                    ? new BasicInstrumentInput(instrumentId, Map.copyOf(values))
                    : new BasicInstrumentInput(instrumentId, Map.of()));
        }
        return inputs;
    }

    private List<BasicOrderCandidate> acceptedOf(BasicExecutionResult execution, UUID evaluationId) {
        List<BasicOrderCandidate> candidates = new ArrayList<>();
        execution.decisions().stream()
                .filter(decision -> decision.status() == BasicDecisionStatus.CANDIDATE)
                .forEach(decision -> candidates.add(new BasicOrderCandidate(
                        derived("candidate",
                                evaluationId + ":" + decision.flowId() + ":" + decision.instrumentId()),
                        decision.flowId(),
                        decision.instrumentId(),
                        decision.side(),
                        decision.buyAllocation(),
                        Map.of("evaluationId", evaluationId.toString()))));
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
            candidates.add(candidate.side() == BasicOrderSide.BUY
                    ? OrderCandidate.allocatedBuy(
                            candidate.candidateId(), candidate.instrumentId(), scope.flowId(),
                            candidate.buyAllocation().orElseThrow().numerator(),
                            candidate.buyAllocation().orElseThrow().denominator(),
                            referencePrice, null, List.of("BASIC_RULE_MATCHED"))
                    : OrderCandidate.heldSell(
                            candidate.candidateId(), candidate.instrumentId(), scope.flowId(),
                            referencePrice, null, List.of("BASIC_RULE_MATCHED")));
        }
        return new OrderCandidateBatch(
                OrderCandidateBatch.ALLOCATION_SCHEMA_VERSION,
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
            PreparedWarmup warmup, BoundedWindowFeatureCalculator calculator) {
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

    private static UUID derived(String kind, String material) {
        return UUID.nameUUIDFromBytes((kind + ":" + material).getBytes(StandardCharsets.UTF_8));
    }

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
        private final OrderedIncrementalFeatureRuntime features;
        private final BoundedWindowFeatureCalculator calculator;
        private final EvaluationWindow window;

        /** The gateway's stream position, which starts wherever the bot joined. */
        private long lastMarketSequence = Long.MIN_VALUE;

        /** The feature runtime's own dense ordering, which must start at zero and never gap. */
        private long localSequence = -1;

        private RegisteredBot(
                UUID botId,
                BasicPlanInterpreter.InterpretedPlan plan,
                OrderedIncrementalFeatureRuntime features,
                BoundedWindowFeatureCalculator calculator,
                EvaluationWindow window) {
            this.botId = botId;
            this.plan = plan;
            this.features = features;
            this.calculator = calculator;
            this.window = window;
        }

        /** True when this event moves the bot forward, and records it when it does. */
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

        private UUID botId() {
            return botId;
        }

        private BasicPlanInterpreter.InterpretedPlan plan() {
            return plan;
        }

        private OrderedIncrementalFeatureRuntime features() {
            return features;
        }

        private BoundedWindowFeatureCalculator calculator() {
            return calculator;
        }

        private EvaluationWindow window() {
            return window;
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
}
