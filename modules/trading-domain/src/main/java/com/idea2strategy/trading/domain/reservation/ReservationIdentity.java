package com.idea2strategy.trading.domain.reservation;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Derived identifiers for the canonical reservation tables.
 *
 * <p>Canonical makes {@code (intent_id, reservation_key)} unique, so deriving the reservation id
 * from exactly that pair is what turns a redelivered reservation request into the row it already
 * wrote. The private command-receipt table is not ported: the same trick applies to the event
 * stream, where {@code (reservation_id, event_key)} is unique.
 */
final class ReservationIdentity {

    private static final UUID RESERVATION_NAMESPACE =
            UUID.fromString("6b7b529d-b01d-5e4f-9f16-f71c5f7fbca4");

    private ReservationIdentity() {}

    /** Canonical {@code resource_reservations.id}. */
    static UUID reservationId(UUID intentId, String reservationKey) {
        MessageDigest digest = digest();
        digest.update(uuidBytes(RESERVATION_NAMESPACE));
        digest.update("resource-reservation:v2".getBytes(StandardCharsets.UTF_8));
        digest.update(uuidBytes(intentId));
        digest.update(reservationKey.getBytes(StandardCharsets.UTF_8));
        return version5(digest.digest());
    }

    private static UUID version5(byte[] hash) {
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
        return new UUID(ByteBuffer.wrap(hash, 0, 8).getLong(), ByteBuffer.wrap(hash, 8, 8).getLong());
    }

    private static byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-1 is unavailable", unavailable);
        }
    }
}
