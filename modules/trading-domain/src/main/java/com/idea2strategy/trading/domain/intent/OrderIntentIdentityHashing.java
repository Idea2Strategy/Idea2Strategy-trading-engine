package com.idea2strategy.trading.domain.intent;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

    static String requestFingerprint(OrderIntentBatchRequest request) {
        MessageDigest digest = digest("SHA-256");
        digest.update("order-intent-batch-request:v1".getBytes(StandardCharsets.UTF_8));
        digest.update(uuidBytes(request.botId()));
        digest.update(uuidBytes(request.evaluationId()));
        digest.update(uuidBytes(request.sourceCandidateBatchId()));
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(request.candidateIds().size()).array());
        request.candidateIds().forEach(candidateId -> digest.update(uuidBytes(candidateId)));
        return HexFormat.of().formatHex(digest.digest());
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
