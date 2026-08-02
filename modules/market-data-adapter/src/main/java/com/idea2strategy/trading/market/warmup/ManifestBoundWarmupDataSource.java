package com.idea2strategy.trading.market.warmup;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.idea2strategy.trading.strategy.runtime.warmup.DatasetManifestSnapshot;
import com.idea2strategy.trading.strategy.runtime.warmup.DatasetManifestStatus;
import com.idea2strategy.trading.strategy.runtime.warmup.DatasetObjectSnapshot;
import com.idea2strategy.trading.strategy.runtime.warmup.FeatureObservation;
import com.idea2strategy.trading.strategy.runtime.warmup.WarmupDataSnapshot;
import com.idea2strategy.trading.strategy.runtime.warmup.WarmupDataSource;
import com.idea2strategy.trading.strategy.runtime.warmup.WarmupException;
import com.idea2strategy.trading.strategy.runtime.warmup.WarmupFailure;
import com.idea2strategy.trading.strategy.runtime.warmup.WarmupFeatureSeries;
import com.idea2strategy.trading.strategy.runtime.warmup.WarmupRequest;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

public final class ManifestBoundWarmupDataSource implements WarmupDataSource {
    private static final String MANIFEST_CONTRACT = "d90.realtime-warmup-manifest";
    private static final String FEATURE_CONTRACT = "d90.warmup-features";
    private static final String FEATURE_SCHEMA = "feature-object-v1";
    private static final String MARKET_EVENTS = "MARKET_EVENTS";
    private static final String WARMUP_FEATURES = "WARMUP_FEATURES";
    private static final Set<String> DATASET_HASH_FIELDS = Set.of(
            "content_hash", "object_kind", "partition_granularity", "partition_start",
            "partition_end", "period_start", "period_end", "shard_key", "part_number",
            "row_count", "schema_version");
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    private final WarmupBundleStore store;
    private final String manifestKey;
    private final ObjectMapper json;

    public ManifestBoundWarmupDataSource(WarmupBundleStore store, String manifestKey) {
        this(store, manifestKey, new ObjectMapper());
    }

    ManifestBoundWarmupDataSource(WarmupBundleStore store, String manifestKey, ObjectMapper json) {
        this.store = Objects.requireNonNull(store, "store");
        if (manifestKey == null || manifestKey.isBlank()) {
            throw new IllegalArgumentException("manifestKey must not be blank");
        }
        this.manifestKey = manifestKey;
        this.json = Objects.requireNonNull(json, "json").copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    @Override
    public Optional<WarmupDataSnapshot> load(WarmupRequest request) {
        Objects.requireNonNull(request, "request");
        Optional<byte[]> manifestBytes = read(manifestKey, null);
        if (manifestBytes.isEmpty()) {
            return Optional.empty();
        }
        JsonNode manifest = parse(manifestBytes.get(), WarmupFailure.MANIFEST_SCHEMA_MISMATCH, manifestKey);
        validateManifestEnvelope(manifest);

        List<DatasetObjectSnapshot> snapshots = new ArrayList<>();
        List<JsonNode> marketObjects = new ArrayList<>();
        byte[] featureBytes = null;
        JsonNode objects = requiredArray(manifest, "objects", WarmupFailure.MANIFEST_SCHEMA_MISMATCH);
        for (JsonNode object : objects) {
            String objectKey = text(object, "object_key", WarmupFailure.MANIFEST_SCHEMA_MISMATCH);
            String declaredHash = hash(object, "content_hash", WarmupFailure.MANIFEST_SCHEMA_MISMATCH);
            String schemaVersion = text(object, "schema_version", WarmupFailure.MANIFEST_SCHEMA_MISMATCH);
            String role = text(object, "object_role", WarmupFailure.MANIFEST_SCHEMA_MISMATCH);
            byte[] bytes = read(objectKey, WarmupFailure.MANIFEST_OBJECT_INTEGRITY_MISMATCH)
                    .orElseThrow(() -> failure(WarmupFailure.MANIFEST_OBJECT_INTEGRITY_MISMATCH, objectKey));
            String observedHash = sha256(bytes);
            if (!declaredHash.equals(observedHash)) {
                throw failure(WarmupFailure.MANIFEST_OBJECT_INTEGRITY_MISMATCH, objectKey);
            }
            snapshots.add(new DatasetObjectSnapshot(objectKey, declaredHash, observedHash, schemaVersion));
            if (MARKET_EVENTS.equals(role)) {
                marketObjects.add(object);
            } else if (WARMUP_FEATURES.equals(role)) {
                if (featureBytes != null) {
                    throw failure(WarmupFailure.MANIFEST_OBJECT_SCHEMA_MISMATCH,
                            "multiple WARMUP_FEATURES objects");
                }
                featureBytes = bytes;
            } else {
                throw failure(WarmupFailure.MANIFEST_OBJECT_SCHEMA_MISMATCH,
                        "unsupported object_role: " + role);
            }
        }
        if (marketObjects.isEmpty() || featureBytes == null) {
            throw failure(WarmupFailure.MANIFEST_OBJECT_SCHEMA_MISMATCH,
                    "required MARKET_EVENTS and WARMUP_FEATURES objects are missing");
        }

        String declaredDatasetHash = hash(manifest, "dataset_hash", WarmupFailure.MANIFEST_SCHEMA_MISMATCH);
        if (!declaredDatasetHash.equals(canonicalDatasetHash(marketObjects))) {
            throw failure(WarmupFailure.MANIFEST_DATASET_HASH_MISMATCH, declaredDatasetHash);
        }

        String manifestId = text(manifest, "manifest_id", WarmupFailure.MANIFEST_SCHEMA_MISMATCH);
        DatasetManifestStatus manifestStatus = manifestStatus(manifest);
        JsonNode feature = parse(featureBytes, WarmupFailure.MANIFEST_OBJECT_SCHEMA_MISMATCH, WARMUP_FEATURES);
        validateFeatureEnvelope(feature, manifestId, declaredDatasetHash);
        Map<String, WarmupFeatureSeries> series = parseSeries(feature);

        return Optional.of(new WarmupDataSnapshot(
                new DatasetManifestSnapshot(
                        manifestId,
                        text(manifest, "dataset_id", WarmupFailure.MANIFEST_SCHEMA_MISMATCH),
                        positiveLong(manifest, "revision", WarmupFailure.MANIFEST_SCHEMA_MISMATCH),
                        manifestStatus,
                        Integer.toString(manifest.path("schema_version").asInt()),
                        declaredDatasetHash,
                        snapshots),
                series));
    }

    private void validateManifestEnvelope(JsonNode manifest) {
        if (!MANIFEST_CONTRACT.equals(manifest.path("contract_id").asText())
                || !manifest.path("schema_version").isInt()
                || manifest.path("schema_version").asInt() != 1
                || !MARKET_EVENTS.equals(manifest.path("dataset_hash_scope").asText())) {
            throw failure(WarmupFailure.MANIFEST_SCHEMA_MISMATCH, manifestKey);
        }
    }

    private static DatasetManifestStatus manifestStatus(JsonNode manifest) {
        String status = text(manifest, "status", WarmupFailure.MANIFEST_SCHEMA_MISMATCH);
        try {
            return DatasetManifestStatus.valueOf(status);
        } catch (IllegalArgumentException exception) {
            throw failure(WarmupFailure.MANIFEST_SCHEMA_MISMATCH, "status");
        }
    }

    private void validateFeatureEnvelope(JsonNode feature, String manifestId, String datasetHash) {
        if (!FEATURE_CONTRACT.equals(feature.path("contract_id").asText())
                || !feature.path("schema_version").isInt()
                || feature.path("schema_version").asInt() != 1
                || !FEATURE_SCHEMA.equals(feature.path("object_schema_version").asText())) {
            throw failure(WarmupFailure.MANIFEST_OBJECT_SCHEMA_MISMATCH, WARMUP_FEATURES);
        }
        if (!manifestId.equals(feature.path("manifest_id").asText())
                || !datasetHash.equals(feature.path("dataset_hash").asText())) {
            throw failure(WarmupFailure.REQUIREMENT_MANIFEST_MISMATCH, WARMUP_FEATURES);
        }
    }

    private Map<String, WarmupFeatureSeries> parseSeries(JsonNode feature) {
        Map<String, WarmupFeatureSeries> result = new LinkedHashMap<>();
        for (JsonNode value : requiredArray(
                feature, "series", WarmupFailure.MANIFEST_OBJECT_SCHEMA_MISMATCH)) {
            String requirementId = text(
                    value, "requirement_id", WarmupFailure.MANIFEST_OBJECT_SCHEMA_MISMATCH);
            List<FeatureObservation> observations = new ArrayList<>();
            for (JsonNode observation : requiredArray(
                    value, "observations", WarmupFailure.MANIFEST_OBJECT_SCHEMA_MISMATCH)) {
                try {
                    observations.add(new FeatureObservation(
                            text(observation, "instrument", WarmupFailure.MANIFEST_OBJECT_SCHEMA_MISMATCH),
                            Instant.parse(text(
                                    observation, "observed_at", WarmupFailure.MANIFEST_OBJECT_SCHEMA_MISMATCH)),
                            new BigDecimal(text(
                                    observation, "value", WarmupFailure.MANIFEST_OBJECT_SCHEMA_MISMATCH))));
                } catch (DateTimeParseException | NumberFormatException exception) {
                    throw failure(WarmupFailure.MANIFEST_OBJECT_SCHEMA_MISMATCH, requirementId);
                }
            }
            WarmupFeatureSeries parsed = new WarmupFeatureSeries(
                    requirementId,
                    text(value, "feature_id", WarmupFailure.MANIFEST_OBJECT_SCHEMA_MISMATCH),
                    text(value, "feature_version", WarmupFailure.MANIFEST_OBJECT_SCHEMA_MISMATCH),
                    text(value, "resolution", WarmupFailure.MANIFEST_OBJECT_SCHEMA_MISMATCH),
                    text(value, "manifest_id", WarmupFailure.MANIFEST_OBJECT_SCHEMA_MISMATCH),
                    hash(value, "dataset_hash", WarmupFailure.MANIFEST_OBJECT_SCHEMA_MISMATCH),
                    observations);
            if (result.putIfAbsent(requirementId, parsed) != null) {
                throw failure(WarmupFailure.MANIFEST_OBJECT_SCHEMA_MISMATCH,
                        "duplicate requirement_id: " + requirementId);
            }
        }
        return Map.copyOf(result);
    }

    private String canonicalDatasetHash(List<JsonNode> objects) {
        try {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (JsonNode object : objects) {
                Map<String, Object> row = new TreeMap<>();
                for (String field : DATASET_HASH_FIELDS) {
                    JsonNode value = object.get(field);
                    row.put(field, value == null || value.isNull() ? null : json.treeToValue(value, Object.class));
                }
                rows.add(row);
            }
            rows.sort(Comparator.comparing(this::canonicalJson));
            return sha256(json.writeValueAsBytes(rows));
        } catch (JsonProcessingException exception) {
            throw failure(WarmupFailure.MANIFEST_SCHEMA_MISMATCH, "dataset hash metadata");
        }
    }

    private String canonicalJson(Map<String, Object> value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw failure(WarmupFailure.MANIFEST_SCHEMA_MISMATCH, "dataset hash metadata");
        }
    }

    private Optional<byte[]> read(String key, WarmupFailure readFailure) {
        try {
            return store.read(key);
        } catch (IOException | RuntimeException exception) {
            WarmupFailure failure = readFailure == null
                    ? WarmupFailure.MANIFEST_SCHEMA_MISMATCH
                    : readFailure;
            throw failure(failure, key);
        }
    }

    private JsonNode parse(byte[] bytes, WarmupFailure parseFailure, String detail) {
        try {
            JsonNode document = json.readTree(bytes);
            if (document == null || !document.isObject()) {
                throw failure(parseFailure, detail);
            }
            return document;
        } catch (IOException exception) {
            throw failure(parseFailure, detail);
        }
    }

    private static JsonNode requiredArray(JsonNode parent, String field, WarmupFailure failure) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isArray()) {
            throw failure(failure, field);
        }
        return value;
    }

    private static String text(JsonNode parent, String field, WarmupFailure failure) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw failure(failure, field);
        }
        return value.asText();
    }

    private static String hash(JsonNode parent, String field, WarmupFailure failure) {
        String value = text(parent, field, failure);
        if (!SHA_256.matcher(value).matches()) {
            throw failure(failure, field);
        }
        return value;
    }

    private static long positiveLong(JsonNode parent, String field, WarmupFailure failure) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() <= 0) {
            throw failure(failure, field);
        }
        return value.asLong();
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static WarmupException failure(WarmupFailure failure, String detail) {
        return new WarmupException(failure, detail);
    }
}
