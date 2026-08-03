package com.idea2strategy.trading.domain.intent;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

final class OrderIntentIdentityHashing {

    static final UUID BATCH_NAMESPACE = UUID.fromString("4d4a4d0d-4a2d-5f56-9fb7-93c91a0f8fd5");
    static final UUID INTENT_NAMESPACE = UUID.fromString("98d1b93d-0c0e-5aed-b404-493d273db4ee");

    private OrderIntentIdentityHashing() {
    }

    static UUID version5(UUID namespace, String domainTag, UUID... identifiers) {
        MessageDigest digest = digest("SHA-1");
        digest.update(uuidBytes(namespace));
        digest.update(domainTag.getBytes(StandardCharsets.UTF_8));
        for (UUID identifier : identifiers) {
            digest.update(uuidBytes(identifier));
        }
        return uuidWithVersion5(digest.digest());
    }

    /**
     * Hashes everything the bundle read to compose this batch. The canonical
     * {@code input_state_hash} is what proves a redelivery carried identical inputs, so every decided
     * field takes part; a batch whose decisions differ must not look like a replay.
     */
    /** The liquidation analogue of {@link #inputStateHash}: what the settlement flattened, and when. */
    static String stopLiquidationInputHash(
            java.util.UUID botId,
            java.util.UUID partitionId,
            java.util.UUID sourceEventId,
            java.time.Instant composedAt,
            java.util.List<OrderIntentRequest> intents) {
        MessageDigest digest = digest("SHA-256");
        writeTag(digest, "stop-liquidation-batch-input:v1");
        writeUuid(digest, botId);
        writeUuid(digest, partitionId);
        writeUuid(digest, sourceEventId);
        writeString(digest, composedAt.toString());
        writeInt(digest, intents.size());
        intents.forEach(intent -> writeIntentRequest(digest, intent));
        return HexFormat.of().formatHex(digest.digest());
    }

    static String inputStateHash(OrderIntentBatchRequest request) {
        MessageDigest digest = digest("SHA-256");
        writeTag(digest, "order-intent-batch-input:v2");
        writeUuid(digest, request.botId());
        writeUuid(digest, request.partitionId());
        writeUuid(digest, request.sourceEventId());
        writeUuid(digest, request.evaluationId());
        writeUuid(digest, request.sourceCandidateBatchId());
        writeString(digest, request.composedAt().toString());
        writeInt(digest, request.intents().size());
        request.intents().forEach(intent -> writeIntentRequest(digest, intent));
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Pins the conflict and netting rules this batch was composed under.
     *
     * <p>Composition and conflict resolution are one versioned ruleset in this engine, so the hash is
     * derived from that version rather than from a separate published policy row. When a distinct
     * conflict policy source appears, this is the single place that has to start reading it.
     */
    static String conflictPolicyHash(String compositionRulesVersion) {
        MessageDigest digest = digest("SHA-256");
        writeTag(digest, "order-intent-conflict-policy:v1");
        writeString(digest, compositionRulesVersion);
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Hashes what the batch actually produced, which is what {@code result_hash} records. */
    static String resultHash(List<OrderIntent> intents) {
        MessageDigest digest = digest("SHA-256");
        writeTag(digest, "order-intent-batch-result:v1");
        writeInt(digest, intents.size());
        for (OrderIntent intent : intents) {
            writeUuid(digest, intent.intentId());
            writeString(digest, intent.intentKey());
            writeString(digest, intent.request().decision().name());
            writeString(digest, intent.request().decisionReasonCode());
            writeDecimal(digest, intent.request().finalQuantity());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void writeIntentRequest(MessageDigest digest, OrderIntentRequest intent) {
        writeUuid(digest, intent.candidateId());
        writeUuid(digest, intent.flowId());
        writeUuid(digest, intent.instrumentId());
        writeString(digest, intent.side().name());
        writeString(digest, intent.positionEffect().name());
        writeString(digest, intent.orderType().name());
        writeString(digest, intent.timeInForce().name());
        writeDecimal(digest, intent.requestedQuantity());
        writeDecimal(digest, intent.limitPrice());
        writeDecimal(digest, intent.stopPrice());
        writeInstant(digest, intent.requestedExpiresAt());
        writeString(digest, intent.decision().name());
        writeString(digest, intent.decisionReasonCode());
        writeDecimal(digest, intent.finalQuantity());
    }

    static <T> T requireNonNull(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }

    static UUID requireVersion5Rfc4122(UUID value, String name) {
        requireNonNull(value, name);
        if (value.version() != 5 || value.variant() != 2) {
            throw new IllegalArgumentException(name + " must be an RFC 4122 version 5 UUID");
        }
        return value;
    }

    static <T> List<T> immutableList(List<T> values, String name) {
        requireNonNull(values, name);
        if (values.stream().anyMatch(value -> value == null)) {
            throw new IllegalArgumentException(name + " must not contain null elements");
        }
        return List.copyOf(values);
    }

    private static void writeTag(MessageDigest digest, String tag) {
        digest.update(tag.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeInt(MessageDigest digest, int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }

    private static void writeUuid(MessageDigest digest, UUID value) {
        digest.update(uuidBytes(value));
    }

    /**
     * Length prefixing keeps two adjacent fields from concatenating into the same bytes as one
     * longer field, and the absent marker keeps a null distinct from an empty value.
     */
    private static void writeString(MessageDigest digest, String value) {
        if (value == null) {
            writeInt(digest, -1);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeInt(digest, bytes.length);
        digest.update(bytes);
    }

    /** Normalised so {@code 1.50} and {@code 1.5} are the same reserved amount. */
    private static void writeDecimal(MessageDigest digest, BigDecimal value) {
        writeString(digest, value == null ? null : value.stripTrailingZeros().toPlainString());
    }

    private static void writeInstant(MessageDigest digest, Instant value) {
        writeString(digest, value == null ? null : value.toString());
    }

    private static byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(2 * Long.BYTES)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(algorithm + " is unavailable", exception);
        }
    }

    private static UUID uuidWithVersion5(byte[] hash) {
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
        return new UUID(
                ByteBuffer.wrap(hash, 0, Long.BYTES).getLong(),
                ByteBuffer.wrap(hash, Long.BYTES, Long.BYTES).getLong());
    }
}
