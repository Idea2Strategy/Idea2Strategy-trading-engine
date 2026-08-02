package com.idea2strategy.trading.market.warmup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.idea2strategy.trading.strategy.runtime.warmup.PreparedWarmup;
import com.idea2strategy.trading.strategy.runtime.warmup.StartupWarmupCoordinator;
import com.idea2strategy.trading.strategy.runtime.warmup.WarmupException;
import com.idea2strategy.trading.strategy.runtime.warmup.WarmupFailure;
import com.idea2strategy.trading.strategy.runtime.warmup.WarmupRequest;
import com.idea2strategy.trading.strategy.runtime.warmup.WarmupRequirement;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManifestBoundWarmupDataSourceTest {
    private static final String INSTRUMENT_ID = "8a35e6b5-cf84-4f63-920d-57c1f1b95df0";
    private static final String MANIFEST_ID = "40cd1f5f-3b1e-5ed7-a440-050d6f17ec45";
    private static final String DATASET_ID = "06c9f65d-6487-57cb-9dcd-4d9651a3d6fd";
    private static final String MARKET_KEY =
            "warmup/session_date_et=2026-07-31/market-events-2026-07-31.parquet";
    private static final String FEATURE_KEY =
            "warmup/session_date_et=2026-07-31/warmup-features-2026-07-31.json";
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    @TempDir
    Path root;

    @Test
    void loadsTheExactProducerBundleAndPreparesBoundFeatures() throws IOException {
        writeBundle(true);

        PreparedWarmup prepared = coordinator().prepare(request(1));

        assertEquals(MANIFEST_ID, prepared.manifestId());
        assertEquals(DATASET_ID, prepared.datasetId());
        assertEquals(Set.of("close-entry"), prepared.seriesByRequirementId().keySet());
        assertEquals("210.2", prepared.seriesByRequirementId().get("close-entry")
                .observations().getFirst().value().toPlainString());
    }

    @Test
    void blocksStartupWhenADeclaredFeatureObjectIsCorrupted() throws IOException {
        writeBundle(true);
        Files.writeString(root.resolve(Path.of(FEATURE_KEY).getFileName()), "corrupt",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        WarmupException exception = assertThrows(WarmupException.class,
                () -> coordinator().prepare(request(1)));

        assertEquals(WarmupFailure.MANIFEST_OBJECT_INTEGRITY_MISMATCH, exception.failure());
    }

    @Test
    void blocksStartupWhenMarketObjectMetadataDoesNotMatchTheDatasetHash() throws IOException {
        Map<String, Object> manifest = writeBundle(true);
        manifest.put("dataset_hash", "0".repeat(64));
        writeJson(root.resolve("manifest.json"), manifest);

        WarmupException exception = assertThrows(WarmupException.class,
                () -> coordinator().prepare(request(1)));

        assertEquals(WarmupFailure.MANIFEST_DATASET_HASH_MISMATCH, exception.failure());
    }

    @Test
    void blocksStartupWhenTheProducerManifestSchemaIsIncompatible() throws IOException {
        Map<String, Object> manifest = writeBundle(true);
        manifest.put("schema_version", 2);
        writeJson(root.resolve("manifest.json"), manifest);

        WarmupException exception = assertThrows(WarmupException.class,
                () -> coordinator().prepare(request(1)));

        assertEquals(WarmupFailure.MANIFEST_SCHEMA_MISMATCH, exception.failure());
    }

    @Test
    void keepsRequiredCoverageFailClosedAfterLoadingTheRealFeatureObject() throws IOException {
        writeBundle(false);

        WarmupException exception = assertThrows(WarmupException.class,
                () -> coordinator().prepare(request(1)));

        assertEquals(WarmupFailure.REQUIREMENT_COVERAGE_INSUFFICIENT, exception.failure());
    }

    @Test
    void missingManifestIsReportedAsNoSnapshot() {
        ManifestBoundWarmupDataSource source = new ManifestBoundWarmupDataSource(
                new FileWarmupBundleStore(root), "manifest.json");

        assertFalse(source.load(request(1)).isPresent());
    }

    private StartupWarmupCoordinator coordinator() {
        return new StartupWarmupCoordinator(
                new ManifestBoundWarmupDataSource(new FileWarmupBundleStore(root), "manifest.json"),
                "1",
                Set.of("warmup-bars-v1", "feature-object-v1"));
    }

    private static WarmupRequest request(int requiredObservations) {
        return new WarmupRequest(
                UUID.fromString("b274523a-e318-4b7b-81bd-d3458738a690"),
                UUID.fromString("314d3ed1-b7ca-4432-94e3-a13b53ed122d"),
                Instant.parse("2026-07-31T14:31:00Z"),
                Set.of(new WarmupRequirement(
                        "close-entry", "close", "1.0.0", Set.of(INSTRUMENT_ID), "PT1M",
                        requiredObservations)));
    }

    private Map<String, Object> writeBundle(boolean includeObservation) throws IOException {
        byte[] marketBytes = "deterministic-parquet-bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(root.resolve(Path.of(MARKET_KEY).getFileName()), marketBytes);

        Map<String, Object> marketObject = object(
                MARKET_KEY, sha256(marketBytes), "PARQUET", "MARKET_EVENTS", "warmup-bars-v1", 1);
        String datasetHash = datasetHash(marketObject);
        List<Map<String, Object>> observations = includeObservation
                ? List.of(Map.of(
                        "instrument", INSTRUMENT_ID,
                        "observed_at", "2026-07-31T14:30:00Z",
                        "value", "210.2"))
                : List.of();
        Map<String, Object> feature = new LinkedHashMap<>();
        feature.put("contract_id", "d90.warmup-features");
        feature.put("schema_version", 1);
        feature.put("object_schema_version", "feature-object-v1");
        feature.put("manifest_id", MANIFEST_ID);
        feature.put("dataset_hash", datasetHash);
        feature.put("series", List.of(Map.of(
                "requirement_id", "close-entry",
                "feature_id", "close",
                "feature_version", "1.0.0",
                "resolution", "PT1M",
                "manifest_id", MANIFEST_ID,
                "dataset_hash", datasetHash,
                "observations", observations)));
        Path featurePath = root.resolve(Path.of(FEATURE_KEY).getFileName());
        writeJson(featurePath, feature);
        Map<String, Object> featureObject = object(
                FEATURE_KEY, sha256(Files.readAllBytes(featurePath)), "JSON", "WARMUP_FEATURES",
                "feature-object-v1", observations.size());

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("contract_id", "d90.realtime-warmup-manifest");
        manifest.put("schema_version", 1);
        manifest.put("manifest_id", MANIFEST_ID);
        manifest.put("dataset_id", DATASET_ID);
        manifest.put("revision", 1);
        manifest.put("status", "AVAILABLE");
        manifest.put("session_date_et", "2026-07-31");
        manifest.put("dataset_hash", datasetHash);
        manifest.put("dataset_hash_scope", "MARKET_EVENTS");
        manifest.put("objects", List.of(marketObject, featureObject));
        writeJson(root.resolve("manifest.json"), manifest);
        return manifest;
    }

    private static Map<String, Object> object(
            String key, String hash, String kind, String role, String schemaVersion, int rowCount) {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("storage_object_id", UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString());
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

    private static void writeJson(Path path, Map<String, Object> document) throws IOException {
        Files.write(path, JSON.writeValueAsBytes(document));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
