package com.idea2strategy.trading.domain.corporateaction;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Derives the canonical row identity of a corporate-action adjustment.
 *
 * <p>The identity is derived rather than random so a redelivered application resolves to the
 * movement it already wrote. Canonical then carries the uniqueness itself:
 * {@code lot_movements (position_lot_id, bot_event_id)} is UNIQUE, so a losing insert is refused by
 * the database rather than by the private {@code request_fingerprint} receipt column.
 */
final class CorporateActionIdentity {

    private static final UUID NAMESPACE = UUID.fromString("9f1ac0de-4a53-5b7e-9a11-1c6f0d2b8e34");

    private CorporateActionIdentity() {}

    /** One movement per lot per corporate action per official event. */
    static UUID movementId(UUID corporateActionId, UUID positionLotId, UUID botEventId) {
        return derive("corporate-action-movement:v1|" + corporateActionId + "|" + positionLotId
                + "|" + botEventId);
    }

    private static UUID derive(String seed) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(ByteBuffer.allocate(16).putLong(NAMESPACE.getMostSignificantBits())
                    .putLong(NAMESPACE.getLeastSignificantBits()).array());
            digest.update(seed.getBytes(StandardCharsets.UTF_8));
            byte[] hash = digest.digest();
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            return new UUID(
                    ByteBuffer.wrap(hash, 0, 8).getLong(), ByteBuffer.wrap(hash, 8, 8).getLong());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
