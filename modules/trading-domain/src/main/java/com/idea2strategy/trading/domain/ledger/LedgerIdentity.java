package com.idea2strategy.trading.domain.ledger;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

final class LedgerIdentity {
    private static final UUID NAMESPACE = UUID.fromString("6d366399-92cf-5ec8-8104-50a845ef7c38");

    private LedgerIdentity() {}

    static UUID transaction(String canonical) {
        return uuidV5(NAMESPACE, canonical);
    }

    static UUID entry(UUID transactionId, int sequence) {
        return uuidV5(transactionId, "entry|" + sequence);
    }

    private static UUID uuidV5(UUID namespace, String name) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(ByteBuffer.allocate(16).putLong(namespace.getMostSignificantBits())
                    .putLong(namespace.getLeastSignificantBits()).array());
            byte[] hash = digest.digest(name.getBytes(StandardCharsets.UTF_8));
            hash[6] &= 0x0f;
            hash[6] |= 0x50;
            hash[8] &= 0x3f;
            hash[8] |= 0x80;
            ByteBuffer bytes = ByteBuffer.wrap(hash);
            return new UUID(bytes.getLong(), bytes.getLong());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
