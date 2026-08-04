package com.idea2strategy.trading.strategy.runtime.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.idea2strategy.trading.strategy.runtime.plan.ExecutionPlanCompatibility;
import com.idea2strategy.trading.strategy.runtime.plan.LoadedExecutionPlan;
import com.idea2strategy.trading.strategy.runtime.evaluation.PerBotEvaluationQueue;
import com.idea2strategy.trading.strategy.runtime.warmup.PreparedWarmup;
import com.idea2strategy.trading.strategy.runtime.warmup.WarmupException;
import com.idea2strategy.trading.strategy.runtime.warmup.WarmupFailure;
import com.idea2strategy.trading.strategy.runtime.warmup.WarmupRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StrategyBotControlConsumerTest {
    private static final UUID BOT_ID = UUID.fromString("00000000-0000-4000-8000-000000000201");
    private static final String SNAPSHOT_HASH =
            "sha256:1111111111111111111111111111111111111111111111111111111111111111";
    private static final String RUN_KEY =
            "sha256:53edaf910b3986b8070257f4d171374bedc6553bfe61427d21d34532f2bf87fb";
    private static final String STOP_KEY =
            "sha256:f3992c2d2718a329ec14ded3268479e90d8821b7542207dcc9758f3ddf57ba9c";

    @Test
    void duplicateRunAndStopRemainIdempotentAcrossRestart() {
        MapSnapshotSource snapshots = new MapSnapshotSource(Map.of(BOT_ID, compiledPlan()));
        MapCheckpointStore checkpoints = new MapCheckpointStore();
        RecordingLifecycle firstLifecycle = new RecordingLifecycle();
        RecordingWarmupGate firstWarmup = new RecordingWarmupGate(new ArrayList<>());
        StrategyBotControlConsumer first = consumer(snapshots, checkpoints, firstLifecycle, firstWarmup);

        assertEquals(BotControlResult.STARTED, first.consume(runEnvelope(RUN_KEY, "00000000-0000-4000-8000-000000000211")));
        assertEquals(BotControlResult.DUPLICATE_IGNORED,
                first.consume(runEnvelope(RUN_KEY, "00000000-0000-4000-8000-000000000211")));
        assertEquals(BotControlResult.STOPPED, first.consume(stopEnvelope(STOP_KEY)));
        assertEquals(BotControlResult.DUPLICATE_IGNORED, first.consume(stopEnvelope(STOP_KEY)));

        assertEquals(1, firstLifecycle.starts);
        assertEquals(1, firstWarmup.calls);
        assertEquals(1, firstLifecycle.stops);
        assertEquals(BOT_ID, firstLifecycle.loadedPlan.botId());
        assertEquals("basic-compiled-plan.v1", firstLifecycle.loadedPlan.planSchemaVersion());
        assertEquals(SNAPSHOT_HASH, firstLifecycle.loadedPlan.runtimeState().get("snapshotHash"));
        assertFalse(first.canAcceptEvaluation(BOT_ID));

        RecordingLifecycle restartedLifecycle = new RecordingLifecycle();
        RecordingWarmupGate restartedWarmup = new RecordingWarmupGate(new ArrayList<>());
        StrategyBotControlConsumer restarted = consumer(snapshots, checkpoints, restartedLifecycle, restartedWarmup);
        assertEquals(BotControlResult.DUPLICATE_IGNORED,
                restarted.consume(runEnvelope(RUN_KEY, "00000000-0000-4000-8000-000000000211")));
        assertEquals(0, restartedLifecycle.starts);
        assertEquals(0, restartedWarmup.calls);
        assertFalse(restarted.canAcceptEvaluation(BOT_ID));
        BotEvaluationBlockedException blocked = assertThrows(
                BotEvaluationBlockedException.class,
                () -> restarted.requireEvaluationAllowed(BOT_ID));
        assertEquals(BotControlFailure.EVALUATION_BLOCKED, blocked.failure());
        try (PerBotEvaluationQueue queue = new PerBotEvaluationQueue(Runnable::run, restarted)) {
            assertThrows(BotEvaluationBlockedException.class, () -> queue.submit(BOT_ID, () -> "forbidden"));
        }
    }

    @Test
    void runWarmsEveryCompiledRequirementBeforeStartingAndCheckpointing() {
        MapSnapshotSource snapshots = new MapSnapshotSource(Map.of(BOT_ID, compiledPlan()));
        List<String> events = new ArrayList<>();
        RecordingCheckpointStore checkpoints = new RecordingCheckpointStore(events);
        RecordingLifecycle lifecycle = new RecordingLifecycle(events);
        RecordingWarmupGate warmup = new RecordingWarmupGate(events);
        StrategyBotControlConsumer consumer = consumer(snapshots, checkpoints, lifecycle, warmup);

        assertEquals(BotControlResult.STARTED,
                consumer.consume(runEnvelope(RUN_KEY, "00000000-0000-4000-8000-000000000211")));

        WarmupRequest request = warmup.request;
        assertEquals(BOT_ID, request.botId());
        assertEquals(lifecycle.loadedPlan.releaseId(), request.releaseId());
        assertEquals(Instant.parse("2026-08-03T13:30:00Z"), request.startupTime());
        assertEquals(1, request.requirements().size());
        var requirement = request.requirements().iterator().next();
        assertEquals("rsi-14-pt1m", requirement.requirementId());
        assertEquals("00000000-0000-4000-8000-000000000401", requirement.featureId());
        assertEquals("1.0.0", requirement.featureVersion());
        assertEquals(Set.of("00000000-0000-4000-8000-000000000301"), requirement.instruments());
        assertEquals("PT1M", requirement.resolution());
        assertEquals(14, requirement.requiredObservations());
        assertEquals(List.of("warmup", "start", "checkpoint"), events);
        // The prepared history reaches the lifecycle rather than being resolved and dropped. It is
        // the only thing that can seed a bounded-window feature, so a runtime handed nothing would
        // report warm-up incomplete for its first fifteen bars of live data instead of evaluating.
        assertSame(warmup.prepared, lifecycle.receivedWarmup);
    }

    @Test
    void warmupFailureDoesNotStartOrWriteRunningCheckpoint() {
        MapSnapshotSource snapshots = new MapSnapshotSource(Map.of(BOT_ID, compiledPlan()));
        MapCheckpointStore checkpoints = new MapCheckpointStore();
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        BotStartupGate unavailable = (request, starter) -> {
            throw new WarmupException(WarmupFailure.SNAPSHOT_NOT_FOUND, request.botId().toString());
        };
        StrategyBotControlConsumer consumer = consumer(snapshots, checkpoints, lifecycle, unavailable);

        WarmupException failure = assertThrows(WarmupException.class,
                () -> consumer.consume(runEnvelope(RUN_KEY, "00000000-0000-4000-8000-000000000211")));

        assertEquals(WarmupFailure.SNAPSHOT_NOT_FOUND, failure.failure());
        assertEquals(0, lifecycle.starts);
        assertEquals(Optional.empty(), checkpoints.find(BOT_ID));
    }

    @Test
    void stopDeliveredBeforeRunPermanentlyWins() {
        MapSnapshotSource snapshots = new MapSnapshotSource(Map.of(BOT_ID, compiledPlan()));
        MapCheckpointStore checkpoints = new MapCheckpointStore();
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        StrategyBotControlConsumer consumer = consumer(snapshots, checkpoints, lifecycle);

        assertEquals(BotControlResult.STOPPED, consumer.consume(stopEnvelope(STOP_KEY)));
        assertEquals(BotControlResult.OUT_OF_ORDER_IGNORED,
                consumer.consume(runEnvelope(RUN_KEY, "00000000-0000-4000-8000-000000000211")));
        assertEquals(0, lifecycle.starts);
        assertEquals(1, lifecycle.stops);
        assertFalse(consumer.canAcceptEvaluation(BOT_ID));
    }

    @Test
    void rejectsSnapshotHashMismatchBeforeStartingRuntime() {
        MapSnapshotSource snapshots = new MapSnapshotSource(Map.of(BOT_ID, compiledPlan()));
        MapCheckpointStore checkpoints = new MapCheckpointStore();
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        StrategyBotControlConsumer consumer = consumer(snapshots, checkpoints, lifecycle);

        StrategyBotOutboxEnvelope mismatched = new StrategyBotOutboxEnvelope(
                UUID.fromString("00000000-0000-4000-8000-000000000211"),
                "strategy-bot",
                BOT_ID,
                1,
                "BOT_RUN_COMMAND",
                "strategy-bot.v1",
                RUN_KEY,
                runPayload(RUN_KEY, "00000000-0000-4000-8000-000000000211")
                        .replace(SNAPSHOT_HASH,
                                "sha256:9999999999999999999999999999999999999999999999999999999999999999"));

        StrategyBotControlException failure = assertThrows(
                StrategyBotControlException.class,
                () -> consumer.consume(mismatched));

        assertEquals(BotControlFailure.SNAPSHOT_HASH_MISMATCH, failure.failure());
        assertEquals(0, lifecycle.starts);
        assertFalse(consumer.canAcceptEvaluation(BOT_ID));
    }

    @Test
    void rejectsUnsupportedVersionAndOutboxMetadataMismatch() {
        StrategyBotContractCodec codec = new StrategyBotContractCodec();
        StrategyBotOutboxEnvelope unsupported = new StrategyBotOutboxEnvelope(
                UUID.fromString("00000000-0000-4000-8000-000000000211"),
                "strategy-bot",
                BOT_ID,
                1,
                "BOT_RUN_COMMAND",
                "strategy-bot.v2",
                RUN_KEY,
                runPayload(RUN_KEY, "00000000-0000-4000-8000-000000000211")
                        .replace("strategy-bot.v1", "strategy-bot.v2"));
        StrategyBotControlException unsupportedFailure = assertThrows(
                StrategyBotControlException.class,
                () -> codec.decodeCommand(unsupported));
        assertEquals(BotControlFailure.UNSUPPORTED_CONTRACT_VERSION, unsupportedFailure.failure());

        StrategyBotOutboxEnvelope mismatchedKey = new StrategyBotOutboxEnvelope(
                UUID.fromString("00000000-0000-4000-8000-000000000211"),
                "strategy-bot",
                BOT_ID,
                1,
                "BOT_RUN_COMMAND",
                "strategy-bot.v1",
                "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                runPayload(RUN_KEY, "00000000-0000-4000-8000-000000000211"));
        StrategyBotControlException envelopeFailure = assertThrows(
                StrategyBotControlException.class,
                () -> codec.decodeCommand(mismatchedKey));
        assertEquals(BotControlFailure.OUTBOX_METADATA_MISMATCH, envelopeFailure.failure());
    }

    @Test
    void rejectsCompiledPlanWhoseCanonicalChecksumDoesNotMatch() {
        StrategyBotContractCodec codec = new StrategyBotContractCodec();

        StrategyBotControlException failure = assertThrows(
                StrategyBotControlException.class,
                () -> codec.decodeCompiledPlan(compiledPlan().replace("RSI_14", "RSI_99")));

        assertEquals(BotControlFailure.PLAN_INTEGRITY_MISMATCH, failure.failure());
    }

    @Test
    void rejectsAmbiguousWarmupRequirementValuesBeforeChecksumVerification() {
        StrategyBotContractCodec codec = new StrategyBotContractCodec();
        List<String> invalidPlans = List.of(
                compiledPlan().replace("\"resolution\": \"PT1M\"", "\"resolution\": \"PT60S\""),
                compiledPlan().replace(
                        "\"instruments\": [\"00000000-0000-4000-8000-000000000301\"]",
                        "\"instruments\": [\"00000000-0000-4000-8000-000000000301\", \"00000000-0000-4000-8000-000000000301\"]"),
                compiledPlan().replace("\"featureVersion\": \"1.0.0\"", "\"featureVersion\": \"latest\""));

        invalidPlans.forEach(plan -> {
            StrategyBotControlException failure = assertThrows(
                    StrategyBotControlException.class, () -> codec.decodeCompiledPlan(plan));
            assertEquals(BotControlFailure.INVALID_MESSAGE, failure.failure());
        });
    }

    private static StrategyBotControlConsumer consumer(
            StrategyBotSnapshotSource snapshots,
            BotControlCheckpointStore checkpoints,
            BotRuntimeLifecycle lifecycle) {
        return consumer(snapshots, checkpoints, lifecycle, new RecordingWarmupGate(new ArrayList<>()));
    }

    private static StrategyBotControlConsumer consumer(
            StrategyBotSnapshotSource snapshots,
            BotControlCheckpointStore checkpoints,
            BotRuntimeLifecycle lifecycle,
            BotStartupGate warmupGate) {
        return new StrategyBotControlConsumer(
                new StrategyBotContractCodec(),
                snapshots,
                checkpoints,
                lifecycle,
                warmupGate,
                new ExecutionPlanCompatibility(
                        "basic-compiled-plan.v1",
                        StrategyBotExecutionPlanAdapter.RUNTIME_SCHEMA_VERSION,
                        Map.of()));
    }

    private static StrategyBotOutboxEnvelope runEnvelope(String idempotencyKey, String messageId) {
        return new StrategyBotOutboxEnvelope(
                UUID.fromString(messageId),
                "strategy-bot",
                BOT_ID,
                1,
                "BOT_RUN_COMMAND",
                "strategy-bot.v1",
                idempotencyKey,
                runPayload(idempotencyKey, messageId));
    }

    private static StrategyBotOutboxEnvelope stopEnvelope(String idempotencyKey) {
        String messageId = "00000000-0000-4000-8000-000000000212";
        return new StrategyBotOutboxEnvelope(
                UUID.fromString(messageId),
                "strategy-bot",
                BOT_ID,
                2,
                "BOT_STOP_COMMAND",
                "strategy-bot.v1",
                idempotencyKey,
                """
                {
                  "metadata": {
                    "contractVersion": "strategy-bot.v1",
                    "messageType": "BOT_STOP_COMMAND",
                    "messageId": "%s",
                    "occurredAt": "2026-07-31T12:01:00Z",
                    "correlationId": "00000000-0000-4000-8000-000000000202",
                    "idempotencyKey": "%s"
                  },
                  "botId": "%s",
                  "expectedSnapshotHash": "%s",
                  "reasonCode": "USER_REQUESTED"
                }
                """.formatted(messageId, idempotencyKey, BOT_ID, SNAPSHOT_HASH));
    }

    private static String runPayload(String idempotencyKey, String messageId) {
        return """
                {
                  "metadata": {
                    "contractVersion": "strategy-bot.v1",
                    "messageType": "BOT_RUN_COMMAND",
                    "messageId": "%s",
                    "occurredAt": "2026-07-31T12:00:00Z",
                    "correlationId": "00000000-0000-4000-8000-000000000202",
                    "idempotencyKey": "%s"
                  },
                  "botId": "%s",
                  "expectedSnapshotHash": "%s",
                  "executionEligibleFrom": "2026-08-03T13:30:00Z"
                }
                """.formatted(messageId, idempotencyKey, BOT_ID, SNAPSHOT_HASH);
    }

    private static String compiledPlan() {
        return """
                {
                  "contractVersion": "strategy-bot.v1",
                  "schemaVersion": "basic-compiled-plan.v1",
                  "elementCatalogVersion": "basic-elements:2026-07-31",
                  "instrumentCatalogVersion": "us-supported-universe:2026-07-31",
                  "compilerVersion": "basic-compiler:1.0.0",
                  "requiredFeatureSetHash": "sha256:3333333333333333333333333333333333333333333333333333333333333333",
                  "requiredFeatures": [
                    {
                      "requirementId": "rsi-14-pt1m",
                      "featureId": "00000000-0000-4000-8000-000000000401",
                      "featureVersion": "1.0.0",
                      "instruments": ["00000000-0000-4000-8000-000000000301"],
                      "resolution": "PT1M",
                      "requiredObservations": 14
                    }
                  ],
                  "executionSnapshot": {
                    "immutableStrategyVersion": {
                      "snapshotSchemaVersion": "basic-launch-snapshot.v1",
                      "semanticHash": "sha256:2222222222222222222222222222222222222222222222222222222222222222",
                      "snapshotHash": "%s"
                    },
                    "mode": "BASIC",
                    "initialCashAmount": "100000.00000000",
                    "currency": "USD",
                    "partitions": [
                      {
                        "key": "partition-1",
                        "budgetCapBps": 10000,
                        "flows": [
                          {
                            "key": "flow-1",
                            "officialInstrumentIds": ["00000000-0000-4000-8000-000000000301"]
                          }
                        ]
                      }
                    ]
                  },
                  "steps": [
                    {
                      "sequence": 1,
                      "operation": "LOAD_FEATURE",
                      "arguments": {"feature": "RSI_14", "resolution": "1m"}
                    },
                    {
                      "sequence": 2,
                      "operation": "COMPARE",
                      "arguments": {"operator": "LT", "threshold": "30"}
                    },
                    {
                      "sequence": 3,
                      "operation": "EMIT_ORDER_CANDIDATE",
                      "arguments": {"allocation": "EQUAL", "orderType": "MARKET", "side": "BUY"}
                    }
                  ],
                  "planChecksum": "sha256:88d61198d46dce161c2a929702a7fd1cee5c9b044c470d2590b96f3825fcacb3"
                }
                """.formatted(SNAPSHOT_HASH);
    }

    private record MapSnapshotSource(Map<UUID, String> snapshots) implements StrategyBotSnapshotSource {
        @Override
        public Optional<String> findCompiledPlan(UUID botId) {
            return Optional.ofNullable(snapshots.get(botId));
        }
    }

    private static final class MapCheckpointStore implements BotControlCheckpointStore {
        private final Map<UUID, BotControlCheckpoint> checkpoints = new HashMap<>();

        @Override
        public Optional<BotControlCheckpoint> find(UUID botId) {
            return Optional.ofNullable(checkpoints.get(botId));
        }

        @Override
        public void save(BotControlCheckpoint checkpoint) {
            checkpoints.put(checkpoint.botId(), checkpoint);
        }
    }

    private static final class RecordingCheckpointStore implements BotControlCheckpointStore {
        private final MapCheckpointStore delegate = new MapCheckpointStore();
        private final List<String> events;

        private RecordingCheckpointStore(List<String> events) {
            this.events = events;
        }

        @Override
        public Optional<BotControlCheckpoint> find(UUID botId) {
            return delegate.find(botId);
        }

        @Override
        public void save(BotControlCheckpoint checkpoint) {
            events.add("checkpoint");
            delegate.save(checkpoint);
        }
    }

    private static final class RecordingWarmupGate implements BotStartupGate {
        private final List<String> events;
        private WarmupRequest request;
        private PreparedWarmup prepared;
        private int calls;

        private RecordingWarmupGate(List<String> events) {
            this.events = events;
        }

        @Override
        public PreparedWarmup start(WarmupRequest request, java.util.function.Consumer<PreparedWarmup> starter) {
            this.request = request;
            calls++;
            events.add("warmup");
            prepared = new PreparedWarmup(
                    "manifest-1", "dataset-1", 1,
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", Map.of());
            starter.accept(prepared);
            return prepared;
        }
    }

    private static final class RecordingLifecycle implements BotRuntimeLifecycle {
        private int starts;
        private int stops;
        private LoadedExecutionPlan loadedPlan;
        private PreparedWarmup receivedWarmup;
        private EvaluationWindow receivedWindow;
        private final List<String> events;

        private RecordingLifecycle() {
            this(new ArrayList<>());
        }

        private RecordingLifecycle(List<String> events) {
            this.events = events;
        }

        @Override
        public void start(LoadedExecutionPlan plan,
                com.idea2strategy.trading.strategy.runtime.warmup.PreparedWarmup warmup,
                EvaluationWindow window) {
            events.add("start");
            receivedWarmup = warmup;
            receivedWindow = window;
            starts++;
            loadedPlan = plan;
        }

        @Override
        public void stop(UUID botId, String reasonCode) {
            stops++;
        }
    }
}
