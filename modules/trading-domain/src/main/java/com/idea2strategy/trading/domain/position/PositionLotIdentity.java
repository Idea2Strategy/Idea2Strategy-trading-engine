package com.idea2strategy.trading.domain.position;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Derives the canonical row identities of the position write path.
 *
 * <p>Both identities are derived rather than random so that a redelivered fill resolves to the row
 * it already wrote. Canonical then carries the uniqueness itself:
 * {@code position_lots.opening_fill_allocation_id} is UNIQUE and
 * {@code lot_movements (position_lot_id, bot_event_id)} is UNIQUE, so a losing insert is refused by
 * the database rather than by a private receipt table.
 */
final class PositionLotIdentity {

    private static final UUID NAMESPACE = UUID.fromString("604ff4ee-60c7-5d62-9bfd-e9fca379d9a9");

    private PositionLotIdentity() {}

    /**
     * A lot is opened by one exact fill-component allocation, so the allocation is what names it.
     * The private schema keyed the lot off the fill record, which could not tell apart two
     * components settled by one partial fill.
     */
    static UUID lotId(UUID openingFillAllocationId) {
        return derive("position-lot:v2|" + openingFillAllocationId);
    }

    /** One movement per lot per official event, which is exactly what canonical makes unique. */
    static UUID movementId(UUID positionLotId, UUID botEventId) {
        return derive("lot-movement:v1|" + positionLotId + "|" + botEventId);
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
            return new UUID(ByteBuffer.wrap(hash, 0, 8).getLong(), ByteBuffer.wrap(hash, 8, 8).getLong());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
