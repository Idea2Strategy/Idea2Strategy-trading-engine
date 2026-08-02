package com.idea2strategy.trading.domain.position;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

final class PositionLotIdentity {
    private static final UUID NAMESPACE = UUID.fromString("604ff4ee-60c7-5d62-9bfd-e9fca379d9a9");
    private PositionLotIdentity() {}
    static UUID lotId(UUID openingFillRecordId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(ByteBuffer.allocate(16).putLong(NAMESPACE.getMostSignificantBits())
                    .putLong(NAMESPACE.getLeastSignificantBits()).array());
            digest.update(("position-lot:v1|" + openingFillRecordId).getBytes(StandardCharsets.UTF_8));
            byte[] hash = digest.digest();
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            return new UUID(ByteBuffer.wrap(hash, 0, 8).getLong(), ByteBuffer.wrap(hash, 8, 8).getLong());
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
