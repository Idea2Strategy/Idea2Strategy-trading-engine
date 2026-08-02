package com.idea2strategy.trading.domain.reservation;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

final class ReservationIdentity {
    private static final UUID RESERVATION_NAMESPACE = UUID.fromString("6b7b529d-b01d-5e4f-9f16-f71c5f7fbca4");
    private static final UUID COMMAND_NAMESPACE = UUID.fromString("a479f195-76d6-5ba0-8f9f-f6cb8a8bf20f");

    private ReservationIdentity() {
    }

    static UUID reservationId(UUID orderId, ReservationResourceType type, String resourceKey) {
        MessageDigest digest = digest("SHA-1");
        digest.update(uuidBytes(RESERVATION_NAMESPACE));
        digest.update("resource-reservation:v1".getBytes(StandardCharsets.UTF_8));
        digest.update(uuidBytes(orderId));
        digest.update(type.name().getBytes(StandardCharsets.UTF_8));
        digest.update(resourceKey.getBytes(StandardCharsets.UTF_8));
        return version5(digest.digest());
    }

    static UUID createCommandId(UUID reservationId) {
        MessageDigest digest = digest("SHA-1");
        digest.update(uuidBytes(COMMAND_NAMESPACE));
        digest.update("resource-reservation-create:v1".getBytes(StandardCharsets.UTF_8));
        digest.update(uuidBytes(reservationId));
        return version5(digest.digest());
    }

    static String fingerprint(
            UUID orderId,
            ReservationResourceType type,
            String resourceKey,
            BigDecimal reserved,
            List<LotReservationAllocation> allocations,
            Instant createdAt) {
        MessageDigest digest = digest("SHA-256");
        write(digest, "resource-reservation-request:v1");
        write(digest, uuidBytes(orderId));
        write(digest, type.name());
        write(digest, resourceKey);
        write(digest, reserved.stripTrailingZeros().toPlainString());
        write(digest, createdAt.toString());
        for (LotReservationAllocation allocation : allocations) {
            write(digest, uuidBytes(allocation.lotId()));
            write(digest, allocation.openedAt().toString());
            write(digest, allocation.reserved().stripTrailingZeros().toPlainString());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static UUID version5(byte[] hash) {
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
        return new UUID(ByteBuffer.wrap(hash, 0, 8).getLong(), ByteBuffer.wrap(hash, 8, 8).getLong());
    }

    private static void write(MessageDigest digest, String value) {
        write(digest, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void write(MessageDigest digest, byte[] value) {
        digest.update(ByteBuffer.allocate(4).putInt(value.length).array());
        digest.update(value);
    }

    private static byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(algorithm + " is unavailable", exception);
        }
    }
}
