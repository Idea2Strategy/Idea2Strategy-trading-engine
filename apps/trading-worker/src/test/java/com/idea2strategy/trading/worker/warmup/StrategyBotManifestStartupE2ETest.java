package com.idea2strategy.trading.worker.warmup;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.idea2strategy.trading.market.warmup.FileWarmupBundleStore;
import com.idea2strategy.trading.market.warmup.ManifestBoundWarmupDataSource;
import com.idea2strategy.trading.strategy.runtime.control.BotControlCheckpoint;
import com.idea2strategy.trading.strategy.runtime.control.BotControlCheckpointStore;
import com.idea2strategy.trading.strategy.runtime.control.BotControlResult;
import com.idea2strategy.trading.strategy.runtime.control.BotRuntimeLifecycle;
import com.idea2strategy.trading.strategy.runtime.control.StrategyBotContractCodec;
import com.idea2strategy.trading.strategy.runtime.control.StrategyBotControlConsumer;
import com.idea2strategy.trading.strategy.runtime.control.StrategyBotExecutionPlanAdapter;
import com.idea2strategy.trading.strategy.runtime.control.StrategyBotOutboxEnvelope;
import com.idea2strategy.trading.strategy.runtime.plan.ExecutionPlanCompatibility;
import com.idea2strategy.trading.strategy.runtime.plan.LoadedExecutionPlan;
import com.idea2strategy.trading.strategy.runtime.warmup.StartupWarmupCoordinator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StrategyBotManifestStartupE2ETest {
    private static final UUID BOT_ID = UUID.fromString("00000000-0000-4000-8000-000000000201");
    private static final String INSTRUMENT_ID = "00000000-0000-4000-8000-000000000301";
    private static final String FEATURE_ID = "00000000-0000-4000-8000-000000000401";
    private static final String MANIFEST_ID = "40cd1f5f-3b1e-5ed7-a440-050d6f17ec45";
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    @TempDir
    Path root;

    @Test
    void realRunIngressLoadsManifestThenStartsAndCheckpoints() throws IOException {
        writeBundle();
        List<String> events = new ArrayList<>();
        RecordingCheckpointStore checkpoints = new RecordingCheckpointStore(events);
        RecordingLifecycle lifecycle = new RecordingLifecycle(events);
        var source = new ManifestBoundWarmupDataSource(new FileWarmupBundleStore(root), "manifest.json");
        var gate = new BotStartupWarmupGate(new StartupWarmupCoordinator(
                source, "1", Set.of("warmup-bars-v1", "feature-object-v1")));
        var consumer = new StrategyBotControlConsumer(
                new StrategyBotContractCodec(),
                ignored -> Optional.of(compiledPlan()),
                checkpoints,
                lifecycle,
                gate,
                new ExecutionPlanCompatibility(
                        "basic-compiled-plan.v1",
                        StrategyBotExecutionPlanAdapter.RUNTIME_SCHEMA_VERSION,
                        Map.of()));

        BotControlResult result = consumer.consume(runEnvelope());

        assertEquals(BotControlResult.STARTED, result);
        assertEquals(List.of("start", "checkpoint"), events);
        assertEquals("RUNNING", checkpoints.saved.status().name());
        assertEquals(1, lifecycle.starts);
    }

    private void writeBundle() throws IOException {
        byte[] marketBytes = "deterministic-parquet-bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(root.resolve("market.parquet"), marketBytes);
        Map<String, Object> marketObject = object(
                "warmup/market.parquet", sha256(marketBytes), "PARQUET", "MARKET_EVENTS", "warmup-bars-v1", 14);
        String datasetHash = datasetHash(marketObject);

        List<Map<String, Object>> observations = new ArrayList<>();
        Instant firstObservation = Instant.parse("2026-07-31T07:00:00Z");
        for (int bar = 0; bar < 14; bar++) {
            observations.add(Map.of(
                    "instrument", INSTRUMENT_ID,
                    "observed_at", firstObservation.plusSeconds(1_800L * bar).toString(),
                    "value", Integer.toString(200 + bar)));
        }
        Map<String, Object> feature = new LinkedHashMap<>();
        feature.put("contract_id", "d90.warmup-features");
        feature.put("schema_version", 1);
        feature.put("object_schema_version", "feature-object-v1");
        feature.put("manifest_id", MANIFEST_ID);
        feature.put("dataset_hash", datasetHash);
        feature.put("series", List.of(Map.of(
                "requirement_id", "rsi-14-pt30m",
                "feature_id", FEATURE_ID,
                "feature_version", "1.0.0",
                "resolution", "PT30M",
                "manifest_id", MANIFEST_ID,
                "dataset_hash", datasetHash,
                "observations", observations)));
        byte[] featureBytes = JSON.writeValueAsBytes(feature);
        Files.write(root.resolve("features.json"), featureBytes);
        Map<String, Object> featureObject = object(
                "warmup/features.json", sha256(featureBytes), "JSON", "WARMUP_FEATURES", "feature-object-v1", 14);

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("contract_id", "d90.realtime-warmup-manifest");
        manifest.put("schema_version", 1);
        manifest.put("manifest_id", MANIFEST_ID);
        manifest.put("dataset_id", "06c9f65d-6487-57cb-9dcd-4d9651a3d6fd");
        manifest.put("revision", 1);
        manifest.put("status", "AVAILABLE");
        manifest.put("dataset_hash", datasetHash);
        manifest.put("dataset_hash_scope", "MARKET_EVENTS");
        manifest.put("objects", List.of(marketObject, featureObject));
        Files.write(root.resolve("manifest.json"), JSON.writeValueAsBytes(manifest));
    }

    private static Map<String, Object> object(
            String key, String hash, String kind, String role, String schemaVersion, int rowCount) {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("object_key", key);
        object.put("content_hash", hash);
        object.put("object_kind", kind);
        object.put("object_role", role);
        object.put("partition_granularity", "DAY");
        object.put("partition_start", "2026-07-31");
        object.put("partition_end", "2026-08-01");
        object.put("period_start", "2026-07-31T04:00:00Z");
        object.put("period_end", "2026-08-01T04:00:00Z");
        object.put("shard_key", "s00-of-01");
        object.put("part_number", 1);
        object.put("row_count", rowCount);
        object.put("schema_version", schemaVersion);
        return object;
    }

    private static String datasetHash(Map<String, Object> object) throws IOException {
        List<String> keys = List.of(
                "content_hash", "object_kind", "partition_granularity", "partition_start",
                "partition_end", "period_start", "period_end", "shard_key", "part_number",
                "row_count", "schema_version");
        Map<String, Object> canonical = new TreeMap<>();
        keys.forEach(key -> canonical.put(key, object.get(key)));
        return sha256(JSON.writeValueAsBytes(List.of(canonical)));
    }

    private static StrategyBotOutboxEnvelope runEnvelope() {
        UUID messageId = UUID.fromString("00000000-0000-4000-8000-000000000211");
        String key = "sha256:53edaf910b3986b8070257f4d171374bedc6553bfe61427d21d34532f2bf87fb";
        String payload = """
                {"metadata":{"contractVersion":"strategy-bot.v1","messageType":"BOT_RUN_COMMAND",
                "messageId":"%s","occurredAt":"2026-07-31T12:00:00Z",
                "correlationId":"00000000-0000-4000-8000-000000000202","idempotencyKey":"%s"},
                "botId":"%s","expectedSnapshotHash":"sha256:%s",
                "executionEligibleFrom":"2026-08-03T13:30:00Z"}
                """.formatted(messageId, key, BOT_ID, "1".repeat(64));
        return new StrategyBotOutboxEnvelope(
                messageId, "strategy-bot", BOT_ID, 1, "BOT_RUN_COMMAND", "strategy-bot.v1", key, payload);
    }

    private static String compiledPlan() {
        return """
                {"contractVersion":"strategy-bot.v1","schemaVersion":"basic-compiled-plan.v1",
                "elementCatalogVersion":"basic-elements:2026-07-31",
                "instrumentCatalogVersion":"us-supported-universe:2026-07-31","compilerVersion":"basic-compiler:1.0.0",
                "requiredFeatureSetHash":"sha256:%s","requiredFeatures":[{"requirementId":"rsi-14-pt30m",
                "featureId":"%s","featureVersion":"1.0.0","instruments":["%s"],
                "resolution":"PT30M","requiredObservations":14}],"executionSnapshot":{"immutableStrategyVersion":{
                "snapshotSchemaVersion":"basic-launch-snapshot.v1","semanticHash":"sha256:%s",
                "snapshotHash":"sha256:%s"},"mode":"BASIC","initialCashAmount":"100000.00000000","currency":"USD",
                "partitions":[{"key":"partition-1","budgetCapBps":10000,"flows":[{"key":"flow-1",
                "officialInstrumentIds":["%s"]}]}]},"steps":[{"sequence":1,"operation":"LOAD_FEATURE",
                "arguments":{"feature":"RSI_14","resolution":"30m"}},{"sequence":2,"operation":"COMPARE",
                "arguments":{"operator":"LT","threshold":"30"}},{"sequence":3,"operation":"EMIT_ORDER_CANDIDATE",
                "arguments":{"allocation":"EQUAL","orderType":"MARKET","side":"BUY"}}],
                "planChecksum":"sha256:fdf0a80a912feeae259871be03c887dcddbba9931426c371f2dc604e8b6496d9"}
                """.formatted("3".repeat(64), FEATURE_ID, INSTRUMENT_ID, "2".repeat(64), "1".repeat(64), INSTRUMENT_ID);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class RecordingCheckpointStore implements BotControlCheckpointStore {
        private final List<String> events;
        private BotControlCheckpoint saved;

        private RecordingCheckpointStore(List<String> events) {
            this.events = events;
        }

        @Override
        public Optional<BotControlCheckpoint> find(UUID botId) {
            return Optional.ofNullable(saved);
        }

        @Override
        public void save(BotControlCheckpoint checkpoint) {
            events.add("checkpoint");
            saved = checkpoint;
        }
    }

    private static final class RecordingLifecycle implements BotRuntimeLifecycle {
        private final List<String> events;
        private int starts;

        private RecordingLifecycle(List<String> events) {
            this.events = events;
        }

        @Override
        public void start(LoadedExecutionPlan plan,
                com.idea2strategy.trading.strategy.runtime.warmup.PreparedWarmup warmup,
                com.idea2strategy.trading.strategy.runtime.control.EvaluationWindow window) {
            events.add("start");
            starts++;
        }

        @Override
        public void stop(UUID botId, String reasonCode) {
        }
    }
}
