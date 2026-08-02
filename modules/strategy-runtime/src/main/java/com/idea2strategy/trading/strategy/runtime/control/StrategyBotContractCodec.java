package com.idea2strategy.trading.strategy.runtime.control;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.trading.strategy.runtime.warmup.WarmupRequirement;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class StrategyBotContractCodec {
    public static final String CONTRACT_VERSION = "strategy-bot.v1";
    private static final String OWNER_DOMAIN = "strategy-bot";
    private static final String RUN = "BOT_RUN_COMMAND";
    private static final String STOP = "BOT_STOP_COMMAND";
    private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern SEMANTIC_VERSION = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+");

    private final ObjectMapper objectMapper;

    public StrategyBotContractCodec() {
        this(new ObjectMapper());
    }

    public StrategyBotContractCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    StrategyBotCommand decodeCommand(StrategyBotOutboxEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        requireSupportedVersion(envelope.eventSchemaVersion());
        JsonNode root = parse(envelope.payloadDocument());
        JsonNode metadataNode = requiredObject(root, "metadata");
        StrategyBotMetadata metadata = new StrategyBotMetadata(
                requiredText(metadataNode, "contractVersion"),
                requiredText(metadataNode, "messageType"),
                requiredUuid(metadataNode, "messageId"),
                requiredInstant(metadataNode, "occurredAt"),
                requiredUuid(metadataNode, "correlationId"),
                requiredSha256(metadataNode, "idempotencyKey"));
        requireSupportedVersion(metadata.contractVersion());
        verifyEnvelope(envelope, metadata);

        UUID botId = requiredUuid(root, "botId");
        if (!OWNER_DOMAIN.equals(envelope.ownerDomain()) || !botId.equals(envelope.aggregateId())) {
            throw failure(BotControlFailure.OUTBOX_METADATA_MISMATCH,
                    "Outbox owner domain or aggregate ID does not match the strategy-bot payload");
        }
        String snapshotHash = requiredSha256(root, "expectedSnapshotHash");
        return switch (metadata.messageType()) {
            case RUN -> new StrategyBotRunCommand(
                    metadata, botId, snapshotHash, requiredInstant(root, "executionEligibleFrom"));
            case STOP -> new StrategyBotStopCommand(
                    metadata, botId, snapshotHash, requiredText(root, "reasonCode"));
            default -> throw failure(BotControlFailure.INVALID_MESSAGE,
                    "Unsupported strategy-bot message type: " + metadata.messageType());
        };
    }

    StrategyBotCompiledPlan decodeCompiledPlan(String payloadDocument) {
        JsonNode root = parse(payloadDocument);
        String contractVersion = requiredText(root, "contractVersion");
        requireSupportedVersion(contractVersion);
        String schemaVersion = requiredText(root, "schemaVersion");
        JsonNode executionSnapshot = requiredObject(root, "executionSnapshot");
        JsonNode immutableVersion = requiredObject(executionSnapshot, "immutableStrategyVersion");
        Set<WarmupRequirement> warmupRequirements = Set.copyOf(requiredWarmupRequirements(root));
        StrategyBotCompiledPlan compiledPlan = new StrategyBotCompiledPlan(
                contractVersion,
                schemaVersion,
                requiredSha256(immutableVersion, "snapshotHash"),
                requiredSha256(root, "planChecksum"),
                warmupRequirements,
                root.toString());
        String calculatedChecksum = planChecksum(root);
        if (!compiledPlan.planChecksum().equals(calculatedChecksum)) {
            throw failure(BotControlFailure.PLAN_INTEGRITY_MISMATCH,
                    compiledPlan.planChecksum() + " != " + calculatedChecksum);
        }
        return compiledPlan;
    }

    private static void verifyEnvelope(StrategyBotOutboxEnvelope envelope, StrategyBotMetadata metadata) {
        if (!envelope.messageId().equals(metadata.messageId())
                || !envelope.eventType().equals(metadata.messageType())
                || !envelope.eventSchemaVersion().equals(metadata.contractVersion())
                || !envelope.idempotencyKey().equals(metadata.idempotencyKey())) {
            throw failure(BotControlFailure.OUTBOX_METADATA_MISMATCH,
                    "Outbox columns do not match strategy-bot payload metadata");
        }
    }

    private JsonNode parse(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (JsonProcessingException exception) {
            throw new StrategyBotControlException(
                    BotControlFailure.INVALID_MESSAGE, "Invalid strategy-bot JSON", exception);
        }
    }

    private static JsonNode requiredObject(JsonNode parent, String name) {
        JsonNode value = parent == null ? null : parent.get(name);
        if (value == null || !value.isObject()) {
            throw failure(BotControlFailure.INVALID_MESSAGE, name + " must be an object");
        }
        return value;
    }

    private static String requiredText(JsonNode parent, String name) {
        JsonNode value = parent == null ? null : parent.get(name);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw failure(BotControlFailure.INVALID_MESSAGE, name + " must be non-blank text");
        }
        return value.textValue();
    }

    private static String requiredSha256(JsonNode parent, String name) {
        String value = requiredText(parent, name);
        if (!SHA256.matcher(value).matches()) {
            throw failure(BotControlFailure.INVALID_MESSAGE, name + " must be a lowercase prefixed SHA-256");
        }
        return value;
    }

    private static UUID requiredUuid(JsonNode parent, String name) {
        try {
            return UUID.fromString(requiredText(parent, name));
        } catch (IllegalArgumentException exception) {
            throw failure(BotControlFailure.INVALID_MESSAGE, name + " must be a UUID");
        }
    }

    private static Instant requiredInstant(JsonNode parent, String name) {
        try {
            return Instant.parse(requiredText(parent, name));
        } catch (DateTimeException exception) {
            throw failure(BotControlFailure.INVALID_MESSAGE, name + " must be an ISO-8601 instant");
        }
    }

    private static void requireSupportedVersion(String version) {
        if (!CONTRACT_VERSION.equals(version)) {
            throw failure(BotControlFailure.UNSUPPORTED_CONTRACT_VERSION,
                    "Unsupported strategy-bot contract version: " + version);
        }
    }

    private static String planChecksum(JsonNode root) {
        JsonNode executionSnapshot = requiredObject(root, "executionSnapshot");
        JsonNode immutableVersion = requiredObject(executionSnapshot, "immutableStrategyVersion");
        StringBuilder material = new StringBuilder()
                .append("contractVersion=").append(requiredText(root, "contractVersion")).append('\n')
                .append("schemaVersion=").append(requiredText(root, "schemaVersion")).append('\n')
                .append("snapshotSchemaVersion=")
                .append(requiredText(immutableVersion, "snapshotSchemaVersion")).append('\n')
                .append("semanticHash=").append(requiredSha256(immutableVersion, "semanticHash")).append('\n')
                .append("snapshotHash=").append(requiredSha256(immutableVersion, "snapshotHash")).append('\n')
                .append("elementCatalogVersion=").append(requiredText(root, "elementCatalogVersion")).append('\n')
                .append("instrumentCatalogVersion=")
                .append(requiredText(root, "instrumentCatalogVersion")).append('\n')
                .append("compilerVersion=").append(requiredText(root, "compilerVersion")).append('\n')
                .append("requiredFeatureSetHash=").append(requiredSha256(root, "requiredFeatureSetHash")).append('\n')
                .append("mode=").append(requiredText(executionSnapshot, "mode")).append('\n')
                .append("initialCashAmount=").append(requiredText(executionSnapshot, "initialCashAmount")).append('\n')
                .append("currency=").append(requiredText(executionSnapshot, "currency"));
        requiredWarmupRequirements(root).forEach(requirement -> material.append('\n')
                .append("requiredFeature=")
                .append("requirementId=").append(requirement.requirementId())
                .append('|').append("featureId=").append(requirement.featureId())
                .append('|').append("featureVersion=").append(requirement.featureVersion())
                .append('|').append("instruments=").append(String.join(",", requirement.instruments().stream().sorted().toList()))
                .append('|').append("resolution=").append(requirement.resolution())
                .append('|').append("requiredObservations=").append(requirement.requiredObservations()));
        JsonNode partitions = requiredArray(executionSnapshot, "partitions");
        if (partitions.isEmpty()) {
            throw failure(BotControlFailure.INVALID_MESSAGE, "partitions must not be empty");
        }
        partitions.forEach(partition -> {
            material.append('\n').append("partition=").append(requiredText(partition, "key"))
                    .append('|').append("budgetCapBps=").append(requiredPositiveInt(partition, "budgetCapBps"));
            JsonNode flows = requiredArray(partition, "flows");
            if (flows.isEmpty()) {
                throw failure(BotControlFailure.INVALID_MESSAGE, "flows must not be empty");
            }
            flows.forEach(flow -> {
                material.append('\n').append("flow=").append(requiredText(flow, "key"))
                        .append('|').append("officialInstrumentIds=");
                JsonNode instruments = requiredArray(flow, "officialInstrumentIds");
                if (instruments.isEmpty()) {
                    throw failure(BotControlFailure.INVALID_MESSAGE, "officialInstrumentIds must not be empty");
                }
                for (int index = 0; index < instruments.size(); index++) {
                    if (index > 0) {
                        material.append(',');
                    }
                    JsonNode instrument = instruments.get(index);
                    if (!instrument.isTextual() || instrument.textValue().isBlank()) {
                        throw failure(BotControlFailure.INVALID_MESSAGE,
                                "officialInstrumentIds must contain non-blank text");
                    }
                    material.append(instrument.textValue());
                }
            });
        });
        JsonNode steps = requiredArray(root, "steps");
        if (steps.isEmpty()) {
            throw failure(BotControlFailure.INVALID_MESSAGE, "steps must not be empty");
        }
        steps.forEach(step -> {
            material.append('\n').append("step=").append(requiredPositiveInt(step, "sequence"))
                    .append('|').append(requiredText(step, "operation"));
            JsonNode arguments = requiredObject(step, "arguments");
            java.util.ArrayList<String> names = new java.util.ArrayList<>();
            arguments.fieldNames().forEachRemaining(names::add);
            names.stream().sorted().forEach(name -> material.append('|').append(name).append('=')
                    .append(requiredText(arguments, name)));
        });
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.toString().getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private static JsonNode requiredArray(JsonNode parent, String name) {
        JsonNode value = parent == null ? null : parent.get(name);
        if (value == null || !value.isArray()) {
            throw failure(BotControlFailure.INVALID_MESSAGE, name + " must be an array");
        }
        return value;
    }

    private static int requiredPositiveInt(JsonNode parent, String name) {
        JsonNode value = parent == null ? null : parent.get(name);
        if (value == null || !value.canConvertToInt() || value.intValue() <= 0) {
            throw failure(BotControlFailure.INVALID_MESSAGE, name + " must be a positive integer");
        }
        return value.intValue();
    }

    private static List<WarmupRequirement> requiredWarmupRequirements(JsonNode root) {
        JsonNode values = requiredArray(root, "requiredFeatures");
        if (values.isEmpty()) {
            throw failure(BotControlFailure.INVALID_MESSAGE, "requiredFeatures must not be empty");
        }
        Set<String> requirementIds = new HashSet<>();
        Set<String> requirementKeys = new HashSet<>();
        List<WarmupRequirement> requirements = new ArrayList<>();
        values.forEach(value -> {
            String requirementId = requiredText(value, "requirementId");
            if (!requirementIds.add(requirementId)) {
                throw failure(BotControlFailure.INVALID_MESSAGE, "requirementId must be unique");
            }
            String featureId = requiredCanonicalUuidText(value, "featureId");
            String featureVersion = requiredText(value, "featureVersion");
            if (!SEMANTIC_VERSION.matcher(featureVersion).matches()) {
                throw failure(BotControlFailure.INVALID_MESSAGE,
                        "featureVersion must be an exact major.minor.patch version");
            }
            List<String> instruments = requiredCanonicalUuidList(value, "instruments");
            String resolution = requiredNormalizedDuration(value, "resolution");
            int requiredObservations = requiredPositiveInt(value, "requiredObservations");
            String key = String.join("|", featureId, featureVersion, resolution, String.join(",", instruments));
            if (!requirementKeys.add(key)) {
                throw failure(BotControlFailure.INVALID_MESSAGE, "duplicate required feature is not allowed");
            }
            requirements.add(new WarmupRequirement(
                    requirementId, featureId, featureVersion, Set.copyOf(instruments),
                    resolution, requiredObservations));
        });
        return List.copyOf(requirements);
    }

    private static String requiredCanonicalUuidText(JsonNode parent, String name) {
        String value = requiredText(parent, name);
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) {
                throw failure(BotControlFailure.INVALID_MESSAGE, name + " must use canonical lowercase UUID format");
            }
            return value;
        } catch (IllegalArgumentException exception) {
            throw failure(BotControlFailure.INVALID_MESSAGE, name + " must use canonical lowercase UUID format");
        }
    }

    private static List<String> requiredCanonicalUuidList(JsonNode parent, String name) {
        JsonNode values = requiredArray(parent, name);
        if (values.isEmpty()) {
            throw failure(BotControlFailure.INVALID_MESSAGE, name + " must not be empty");
        }
        List<String> result = new ArrayList<>();
        values.forEach(value -> {
            if (!value.isTextual()) {
                throw failure(BotControlFailure.INVALID_MESSAGE, name + " must contain UUID text");
            }
            try {
                String instrument = value.textValue();
                UUID parsed = UUID.fromString(instrument);
                if (!parsed.toString().equals(instrument)) {
                    throw failure(BotControlFailure.INVALID_MESSAGE,
                            name + " must use canonical lowercase UUID format");
                }
                result.add(instrument);
            } catch (IllegalArgumentException exception) {
                throw failure(BotControlFailure.INVALID_MESSAGE,
                        name + " must use canonical lowercase UUID format");
            }
        });
        result.sort(String::compareTo);
        if (new HashSet<>(result).size() != result.size()) {
            throw failure(BotControlFailure.INVALID_MESSAGE, name + " must not contain duplicates");
        }
        return List.copyOf(result);
    }

    private static String requiredNormalizedDuration(JsonNode parent, String name) {
        String value = requiredText(parent, name);
        try {
            Duration duration = Duration.parse(value);
            if (duration.isZero() || duration.isNegative() || !duration.toString().equals(value)) {
                throw failure(BotControlFailure.INVALID_MESSAGE,
                        name + " must be a positive normalized ISO-8601 duration");
            }
            return value;
        } catch (DateTimeException exception) {
            throw failure(BotControlFailure.INVALID_MESSAGE,
                    name + " must be a positive normalized ISO-8601 duration");
        }
    }

    private static StrategyBotControlException failure(BotControlFailure failure, String detail) {
        return new StrategyBotControlException(failure, detail);
    }
}
