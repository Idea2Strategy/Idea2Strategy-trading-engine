package com.idea2strategy.trading.domain.execution;

import com.idea2strategy.trading.domain.order.OrderLifecycle;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

final class FillDecisionIdentity {
    private static final UUID NAMESPACE = UUID.fromString("c5c967c1-f08a-5226-a17c-78ff50e9eecd");

    static UUID decisionId(OrderLifecycle order, RecordedMarketSnapshot snapshot) {
        return uuidV5(NAMESPACE, ("realistic-fill-v1|" + order.orderId() + "|" + order.version() + "|"
                + snapshot.snapshotId()).getBytes(StandardCharsets.UTF_8));
    }

    static UUID fillId(UUID decisionId) {
        return uuidV5(NAMESPACE, ("virtual-fill-v1|" + decisionId).getBytes(StandardCharsets.UTF_8));
    }

    static String fingerprint(OrderLifecycle order, RecordedMarketSnapshot s) {
        String value = String.join("|", "realistic-fill-request-v1", order.orderId().toString(),
                Long.toString(order.version()), order.status().name(), decimal(order.cumulativeFilledQuantity()),
                s.snapshotId().toString(), s.instrumentId().toString(), s.observedAt().toString(),
                decimal(s.bidPrice()), decimal(s.bidSize()), decimal(s.askPrice()), decimal(s.askSize()),
                decimal(s.lastTradePrice()), decimal(s.lastTradeSize()), decimal(s.trailingReferencePrice()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String decimal(java.math.BigDecimal value) {
        return value == null ? "-" : value.stripTrailingZeros().toPlainString();
    }

    private static UUID uuidV5(UUID namespace, byte[] name) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(ByteBuffer.allocate(16).putLong(namespace.getMostSignificantBits())
                    .putLong(namespace.getLeastSignificantBits()).array());
            byte[] hash = digest.digest(name);
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            ByteBuffer bytes = ByteBuffer.wrap(hash);
            return new UUID(bytes.getLong(), bytes.getLong());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
