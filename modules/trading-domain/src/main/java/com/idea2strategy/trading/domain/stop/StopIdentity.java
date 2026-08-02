package com.idea2strategy.trading.domain.stop;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Derived identifiers for the stop concern.
 *
 * <p>Canonical storage has no receipt table for a stop settlement, so every identifier this concern
 * needs has to be a pure function of the work it names. That is what makes at-least-once redelivery
 * land on the same canonical row instead of a second one.
 */
final class StopIdentity {

    private StopIdentity() {}

    /** RFC 4122 version 5 identifier, so the same input always names the same row. */
    static UUID uuid5(UUID namespace, String value) {
        byte[] namespaceBytes = new byte[16];
        ByteBuffer.wrap(namespaceBytes)
                .putLong(namespace.getMostSignificantBits())
                .putLong(namespace.getLeastSignificantBits());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(namespaceBytes);
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            ByteBuffer buffer = ByteBuffer.wrap(hash);
            return new UUID(buffer.getLong(), buffer.getLong());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 unavailable", exception);
        }
    }
}
