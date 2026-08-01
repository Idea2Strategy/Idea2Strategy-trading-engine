package com.idea2strategy.trading.domain.order;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

public final class OrderLifecycleIdentity {

    private static final UUID ORDER_NAMESPACE = UUID.fromString("f5023a1c-9ea3-5af4-aad1-4ac4fe0fd66d");
    private static final UUID CREATE_COMMAND_NAMESPACE = UUID.fromString("50404b88-7b2f-5d51-8a1a-1b421403894f");

    private OrderLifecycleIdentity() {
    }

    public static UUID orderId(OrderTerms terms) {
        require(terms, "terms");
        return version5(ORDER_NAMESPACE, "order-lifecycle:v1", terms.intentId());
    }

    public static UUID createCommandId(UUID orderId) {
        return version5(CREATE_COMMAND_NAMESPACE, "order-lifecycle-create:v1", require(orderId, "orderId"));
    }

    public static String requestFingerprint(
            OrderTerms terms, OrderStatus initialStatus, Instant createdAt, String rejectionReason) {
        require(terms, "terms");
        require(initialStatus, "initialStatus");
        require(createdAt, "createdAt");
        MessageDigest digest = digest("SHA-256");
        write(digest, "order-lifecycle-request:v1");
        write(digest, uuidBytes(terms.intentId()));
        write(digest, uuidBytes(terms.candidateId()));
        write(digest, uuidBytes(terms.instrumentId()));
        write(digest, terms.side().name());
        write(digest, decimal(terms.quantity()));
        write(digest, terms.type().name());
        write(digest, terms.timeInForce().name());
        write(digest, decimal(terms.limitPrice()));
        write(digest, decimal(terms.stopPrice()));
        write(digest, decimal(terms.trailPercent()));
        write(digest, instant(terms.expiresAt()));
        write(digest, initialStatus.name());
        write(digest, instant(createdAt));
        write(digest, rejectionReason);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static UUID version5(UUID namespace, String domainTag, UUID identifier) {
        MessageDigest digest = digest("SHA-1");
        digest.update(uuidBytes(namespace));
        digest.update(domainTag.getBytes(StandardCharsets.UTF_8));
        digest.update(uuidBytes(identifier));
        byte[] hash = digest.digest();
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
        return new UUID(
                ByteBuffer.wrap(hash, 0, Long.BYTES).getLong(),
                ByteBuffer.wrap(hash, Long.BYTES, Long.BYTES).getLong());
    }

    private static void write(MessageDigest digest, String field) {
        if (field == null) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
            return;
        }
        write(digest, field.getBytes(StandardCharsets.UTF_8));
    }

    private static void write(MessageDigest digest, byte[] field) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(field.length).array());
        digest.update(field);
    }

    private static String decimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static String instant(Instant value) {
        return value == null ? null : value.toString();
    }

    private static byte[] uuidBytes(UUID value) {
        UUID identifier = require(value, "UUID");
        return ByteBuffer.allocate(2 * Long.BYTES)
                .putLong(identifier.getMostSignificantBits())
                .putLong(identifier.getLeastSignificantBits())
                .array();
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(algorithm + " is unavailable", exception);
        }
    }

    private static <T> T require(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }
}
