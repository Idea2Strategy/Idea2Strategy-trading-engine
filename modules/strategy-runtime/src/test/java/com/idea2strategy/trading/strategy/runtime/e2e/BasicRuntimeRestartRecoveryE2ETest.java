package com.idea2strategy.trading.strategy.runtime.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.idea2strategy.trading.market.alpaca.AlpacaMarketEventNormalizer;
import com.idea2strategy.trading.market.alpaca.AlpacaMarketInput;
import com.idea2strategy.trading.messaging.market.MarketEventEnvelope;
import com.idea2strategy.trading.messaging.market.MarketEventType;
import com.idea2strategy.trading.strategy.runtime.basic.BasicConditionOutcome;
import com.idea2strategy.trading.strategy.runtime.basic.BasicConditionStep;
import com.idea2strategy.trading.strategy.runtime.basic.BasicDecisionStatus;
import com.idea2strategy.trading.strategy.runtime.basic.BasicExecutionRequest;
import com.idea2strategy.trading.strategy.runtime.basic.BasicExecutionResult;
import com.idea2strategy.trading.strategy.runtime.basic.BasicFlow;
import com.idea2strategy.trading.strategy.runtime.basic.BasicInstrumentInput;
import com.idea2strategy.trading.strategy.runtime.basic.BasicOrderSide;
import com.idea2strategy.trading.strategy.runtime.basic.BasicStrategyExecutor;
import com.idea2strategy.trading.strategy.runtime.candidate.BasicCandidateConvergenceResult;
import com.idea2strategy.trading.strategy.runtime.candidate.BasicCandidateConverger;
import com.idea2strategy.trading.strategy.runtime.candidate.BasicOrderCandidate;
import com.idea2strategy.trading.strategy.runtime.incremental.FeatureKey;
import com.idea2strategy.trading.strategy.runtime.incremental.IncrementalFeatureCalculator;
import com.idea2strategy.trading.strategy.runtime.incremental.IncrementalFeatureSnapshot;
import com.idea2strategy.trading.strategy.runtime.incremental.IncrementalFeatureState;
import com.idea2strategy.trading.strategy.runtime.incremental.IncrementalRuntimeState;
import com.idea2strategy.trading.strategy.runtime.incremental.OrderedIncrementalFeatureRuntime;
import com.idea2strategy.trading.strategy.runtime.incremental.RuntimeTrigger;
import com.idea2strategy.trading.strategy.runtime.incremental.RuntimeTriggerType;
import com.idea2strategy.trading.strategy.runtime.judgment.BotJudgmentSnapshot;
import com.idea2strategy.trading.strategy.runtime.judgment.JudgmentEventDraft;
import com.idea2strategy.trading.strategy.runtime.judgment.JudgmentEventType;
import com.idea2strategy.trading.strategy.runtime.judgment.JudgmentSubject;
import com.idea2strategy.trading.strategy.runtime.judgment.OrderedJudgmentJournal;
import com.idea2strategy.trading.strategy.runtime.judgment.RuntimeStateTransition;
import com.idea2strategy.trading.strategy.runtime.warmup.DatasetManifestSnapshot;
import com.idea2strategy.trading.strategy.runtime.warmup.DatasetManifestStatus;
import com.idea2strategy.trading.strategy.runtime.warmup.DatasetObjectSnapshot;
import com.idea2strategy.trading.strategy.runtime.warmup.FeatureObservation;
import com.idea2strategy.trading.strategy.runtime.warmup.PreparedWarmup;
import com.idea2strategy.trading.strategy.runtime.warmup.StartupWarmupCoordinator;
import com.idea2strategy.trading.strategy.runtime.warmup.WarmupDataSnapshot;
import com.idea2strategy.trading.strategy.runtime.warmup.WarmupFeatureSeries;
import com.idea2strategy.trading.strategy.runtime.warmup.WarmupRequest;
import com.idea2strategy.trading.strategy.runtime.warmup.WarmupRequirement;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BasicRuntimeRestartRecoveryE2ETest {
    private static final UUID BOT_ID = UUID.fromString("00000000-0000-4000-8000-000000000801");
    private static final UUID RELEASE_ID = UUID.fromString("00000000-0000-4000-8000-000000000802");
    private static final UUID AAPL_ID = UUID.fromString("00000000-0000-4000-8000-000000000803");
    private static final FeatureKey AVERAGE = new FeatureKey("simple-moving-average", "1.0.0");
    private static final Instant STARTUP_TIME = Instant.parse("2026-07-31T14:30:00Z");
    private static final String HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test
    void recordedFixtureProducesTheSameResultAfterProcessRestart() throws IOException {
        List<RecordedEvent> recorded = loadFixture();
        AlpacaMarketEventNormalizer normalizer = new AlpacaMarketEventNormalizer(Map.of("AAPL", AAPL_ID));
        List<NormalizedEvent> normalized = recorded.stream()
                .map(row -> new NormalizedEvent(row.phase(), normalizer.normalize(row.input())))
                .toList();
        PreparedWarmup warmup = prepareWarmup(normalized);
        List<MarketEventEnvelope> live = normalized.stream()
                .filter(row -> row.phase().equals("LIVE"))
                .map(NormalizedEvent::event)
                .toList();

        EvaluationProcess uninterrupted = EvaluationProcess.start(initialState(warmup));
        BasicCandidateConvergenceResult uninterruptedResult = null;
        for (MarketEventEnvelope event : live) {
            uninterruptedResult = uninterrupted.evaluate(event);
        }

        EvaluationProcess beforeRestart = EvaluationProcess.start(initialState(warmup));
        beforeRestart.evaluate(live.getFirst());
        IncrementalRuntimeState persistedFeatures = beforeRestart.featureState();
        BotJudgmentSnapshot persistedJournal = beforeRestart.judgmentSnapshot();

        EvaluationProcess afterRestart = EvaluationProcess.restore(persistedFeatures, persistedJournal);
        BasicCandidateConvergenceResult recoveredResult = afterRestart.evaluate(live.getLast());

        assertEquals(uninterruptedResult, recoveredResult);
        assertEquals(uninterrupted.featureState(), afterRestart.featureState());
        assertEquals(uninterrupted.judgmentSnapshot(), afterRestart.judgmentSnapshot());
        assertEquals(1, recoveredResult.acceptedCandidates().size());
        assertEquals(BasicOrderSide.BUY, recoveredResult.acceptedCandidates().getFirst().side());
    }

    private static PreparedWarmup prepareWarmup(List<NormalizedEvent> events) {
        List<FeatureObservation> observations = events.stream()
                .filter(row -> row.phase().equals("WARMUP"))
                .map(NormalizedEvent::event)
                .map(event -> new FeatureObservation(
                        event.instrumentId().toString(), event.occurredAt(), event.values().get("price")))
                .toList();
        WarmupFeatureSeries series = new WarmupFeatureSeries(
                "aapl-sma", AVERAGE.featureId(), AVERAGE.version(), "1m", "manifest-c19", HASH, observations);
        DatasetManifestSnapshot manifest = new DatasetManifestSnapshot(
                "manifest-c19",
                "recorded-aapl-c19",
                1,
                DatasetManifestStatus.AVAILABLE,
                "1",
                HASH,
                List.of(new DatasetObjectSnapshot("fixtures/c19/recorded-aapl-market.csv", HASH, HASH, "1")));
        WarmupDataSnapshot snapshot = new WarmupDataSnapshot(manifest, Map.of("aapl-sma", series));
        StartupWarmupCoordinator coordinator = new StartupWarmupCoordinator(
                ignored -> Optional.of(snapshot), "1", "1");
        return coordinator.prepare(new WarmupRequest(
                BOT_ID,
                RELEASE_ID,
                STARTUP_TIME,
                java.util.Set.of(new WarmupRequirement(
                        "aapl-sma",
                        AVERAGE.featureId(),
                        AVERAGE.version(),
                        java.util.Set.of(AAPL_ID.toString()),
                        "1m",
                        2))));
    }

    private static IncrementalFeatureState initialState(PreparedWarmup warmup) {
        List<FeatureObservation> observations = warmup.seriesByRequirementId().get("aapl-sma").observations();
        BigDecimal sum = observations.stream()
                .map(FeatureObservation::value)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal count = BigDecimal.valueOf(observations.size());
        return new IncrementalFeatureState(
                observations.size(), Map.of("sum", sum, "count", count, "average", average(sum, count)));
    }

    private static List<RecordedEvent> loadFixture() throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                BasicRuntimeRestartRecoveryE2ETest.class.getResourceAsStream(
                        "/fixtures/c19/recorded-aapl-market.csv"),
                StandardCharsets.UTF_8))) {
            List<RecordedEvent> events = new ArrayList<>();
            for (String line : reader.lines().skip(1).toList()) {
                String[] values = line.split(",", -1);
                events.add(new RecordedEvent(values[0], new AlpacaMarketInput(
                        MarketEventType.valueOf(values[3]),
                        values[1],
                        values[2],
                        "SIP",
                        Instant.parse(values[4]),
                        Instant.parse(values[5]),
                        Long.parseLong(values[6]),
                        Integer.parseInt(values[7]),
                        Map.of("price", new BigDecimal(values[8])))));
            }
            return List.copyOf(events);
        }
    }

    private static BigDecimal average(BigDecimal sum, BigDecimal count) {
        return sum.divide(count, 8, RoundingMode.HALF_EVEN).stripTrailingZeros();
    }

    private static UUID stableId(String prefix, String value) {
        return UUID.nameUUIDFromBytes((prefix + ":" + value).getBytes(StandardCharsets.UTF_8));
    }

    private record RecordedEvent(String phase, AlpacaMarketInput input) {
    }

    private record NormalizedEvent(String phase, MarketEventEnvelope event) {
    }

    private static final class EvaluationProcess {
        private final OrderedIncrementalFeatureRuntime features;
        private final OrderedJudgmentJournal journal;

        private EvaluationProcess(
                OrderedIncrementalFeatureRuntime features,
                OrderedJudgmentJournal journal) {
            this.features = features;
            this.journal = journal;
        }

        static EvaluationProcess start(IncrementalFeatureState initialState) {
            return new EvaluationProcess(new OrderedIncrementalFeatureRuntime(
                    BOT_ID, -1, List.of(averageCalculator()), Map.of(AVERAGE, initialState)),
                    new OrderedJudgmentJournal());
        }

        static EvaluationProcess restore(
                IncrementalRuntimeState featureState,
                BotJudgmentSnapshot judgmentSnapshot) {
            OrderedJudgmentJournal restoredJournal = new OrderedJudgmentJournal();
            restoredJournal.restore(judgmentSnapshot);
            return new EvaluationProcess(new OrderedIncrementalFeatureRuntime(
                    featureState.botId(),
                    featureState.lastSequence(),
                    List.of(averageCalculator()),
                    featureState.featureStates()), restoredJournal);
        }

        BasicCandidateConvergenceResult evaluate(MarketEventEnvelope marketEvent) {
            IncrementalFeatureSnapshot featureSnapshot = features.process(new RuntimeTrigger(
                    BOT_ID,
                    marketEvent.sequence(),
                    marketEvent.eventId(),
                    RuntimeTriggerType.MARKET,
                    marketEvent.occurredAt(),
                    marketEvent.values()));
            IncrementalFeatureState averageState = featureSnapshot.featureStates().get(AVERAGE);
            UUID evaluationId = stableId("evaluation", marketEvent.eventId());
            BasicFlow flow = new BasicFlow(
                    "buy-above-average",
                    BasicOrderSide.BUY,
                    List.of(AAPL_ID),
                    List.of(new BasicConditionStep("price-above-average", input -> {
                        BigDecimal price = new BigDecimal(input.values().get("price"));
                        BigDecimal currentAverage = new BigDecimal(input.values().get("average"));
                        return new BasicConditionOutcome(
                                price.compareTo(currentAverage) > 0,
                                price.compareTo(currentAverage) > 0 ? "PRICE_ABOVE_AVERAGE" : "PRICE_NOT_ABOVE_AVERAGE",
                                Map.of("price", price.toPlainString(), "average", currentAverage.toPlainString()));
                    })));
            BasicExecutionResult execution = new BasicStrategyExecutor().execute(new BasicExecutionRequest(
                    evaluationId,
                    List.of(flow),
                    Map.of(AAPL_ID, new BasicInstrumentInput(AAPL_ID, Map.of(
                            "price", marketEvent.values().get("price").toPlainString(),
                            "average", averageState.values().get("average").toPlainString())))));
            List<BasicOrderCandidate> candidates = execution.decisions().stream()
                    .filter(decision -> decision.status() == BasicDecisionStatus.CANDIDATE)
                    .map(decision -> new BasicOrderCandidate(
                            stableId("candidate", evaluationId + ":" + decision.flowId() + ":" + decision.instrumentId()),
                            decision.flowId(),
                            decision.instrumentId(),
                            decision.side(),
                            decision.buyAllocation(),
                            Map.of("marketEventId", marketEvent.eventId())))
                    .toList();
            BasicCandidateConvergenceResult result = new BasicCandidateConverger().converge(
                    evaluationId, candidates);
            recordJudgments(execution, result, marketEvent);
            return result;
        }

        IncrementalRuntimeState featureState() {
            return features.state();
        }

        BotJudgmentSnapshot judgmentSnapshot() {
            return journal.snapshot(BOT_ID);
        }

        private void recordJudgments(
                BasicExecutionResult execution,
                BasicCandidateConvergenceResult convergence,
                MarketEventEnvelope marketEvent) {
            execution.decisions().forEach(decision -> {
                JudgmentEventType type = decision.status() == BasicDecisionStatus.CANDIDATE
                        ? JudgmentEventType.CONDITION_SATISFIED
                        : JudgmentEventType.FIRST_CONDITION_FAILED;
                append(new JudgmentEventDraft(
                        stableId("condition", execution.evaluationId() + ":" + decision.flowId()),
                        type,
                        subject(execution.evaluationId(), decision.flowId(), decision.instrumentId(), null),
                        Map.of("marketEventId", marketEvent.eventId(), "status", decision.status().name()),
                        Optional.empty()));
            });
            convergence.acceptedCandidates().forEach(candidate -> append(new JudgmentEventDraft(
                    stableId("candidate-created", candidate.candidateId().toString()),
                    JudgmentEventType.CANDIDATE_CREATED,
                    subject(convergence.evaluationId(), candidate.flowId(), candidate.instrumentId(), candidate.candidateId()),
                    Map.of("side", candidate.side().name()),
                    Optional.empty())));
            long revision = journal.snapshot(BOT_ID).runtimeState().revision();
            append(new JudgmentEventDraft(
                    stableId("runtime-state", execution.evaluationId().toString()),
                    JudgmentEventType.RUNTIME_STATE_CHANGED,
                    subject(execution.evaluationId(), null, null, null),
                    Map.of("reason", "evaluation-completed"),
                    Optional.of(new RuntimeStateTransition(revision, revision + 1, Map.of(
                            "lastEvaluationId", execution.evaluationId().toString(),
                            "lastMarketEventId", marketEvent.eventId())))));
        }

        private void append(JudgmentEventDraft draft) {
            journal.append(BOT_ID, journal.snapshot(BOT_ID).lastSequence(), draft);
        }

        private static JudgmentSubject subject(
                UUID evaluationId,
                String flowId,
                UUID instrumentId,
                UUID candidateId) {
            return new JudgmentSubject(
                    evaluationId,
                    Optional.ofNullable(flowId),
                    Optional.ofNullable(instrumentId),
                    Optional.ofNullable(candidateId));
        }

        private static IncrementalFeatureCalculator averageCalculator() {
            return new IncrementalFeatureCalculator() {
                @Override
                public FeatureKey key() {
                    return AVERAGE;
                }

                @Override
                public IncrementalFeatureState calculate(
                        IncrementalFeatureState current,
                        RuntimeTrigger trigger) {
                    BigDecimal sum = current.values().get("sum").add(trigger.values().get("price"));
                    BigDecimal count = current.values().get("count").add(BigDecimal.ONE);
                    return new IncrementalFeatureState(
                            current.updateCount() + 1,
                            Map.of("sum", sum, "count", count, "average", average(sum, count)));
                }
            };
        }
    }
}
