package com.idea2strategy.trading.domain.fill;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

final class FillIdentity {
    private static final UUID NAMESPACE = UUID.fromString("1a71b718-c435-50fa-a86d-8330b9434ad8");
    private FillIdentity() {}

    static UUID recordId(UUID orderId, String sourceExecutionId, int revision) {
        MessageDigest digest = digest("SHA-1");
        digest.update(bytes(NAMESPACE));
        digest.update((orderId + "|" + sourceExecutionId + "|" + revision).getBytes(StandardCharsets.UTF_8));
        byte[] hash = digest.digest();
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
        return new UUID(ByteBuffer.wrap(hash, 0, 8).getLong(), ByteBuffer.wrap(hash, 8, 8).getLong());
    }

    static String fingerprint(String canonicalPayload) {
        return HexFormat.of().formatHex(digest("SHA-256").digest(canonicalPayload.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }

    private static MessageDigest digest(String algorithm) {
        try { return MessageDigest.getInstance(algorithm); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
