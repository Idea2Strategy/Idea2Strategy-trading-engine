package com.idea2strategy.trading.persistence.reservation;

import com.idea2strategy.trading.domain.reservation.LotReservationAllocation;
import com.idea2strategy.trading.domain.reservation.ReservationEventType;
import com.idea2strategy.trading.domain.reservation.ReservationOpening;
import com.idea2strategy.trading.domain.reservation.ReservationPolicyPins;
import com.idea2strategy.trading.domain.reservation.ReservationPricing;
import com.idea2strategy.trading.domain.reservation.ResourceReservation;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Canonical {@code reservation_events.event_key} and {@code event_hash}.
 *
 * <p>These two columns are what replaces the private command-receipt table. The key is unique per
 * reservation, so a redelivered request lands on the event it already wrote instead of appending a
 * second one; the hash digests the request that produced the event, so a redelivery claiming
 * something different is a conflict rather than a silent no-op.
 */
final class CanonicalReservationIdentity {

    /** Canonical {@code varchar(160)}; every key produced here is well inside it. */
    private static final int KEY_LIMIT = 160;

    private CanonicalReservationIdentity() {}

    /**
     * A reservation is created once, drawn on once per fill and released once per official event,
     * so each of those has a natural key that needs no separate command identifier.
     */
    static String eventKey(ReservationEventType eventType, UUID sourceFillId, UUID botEventId) {
        String key = eventType.requiresSourceFill()
                ? eventType.name() + ":" + sourceFillId
                : eventType == ReservationEventType.CREATED
                        ? ReservationEventType.CREATED.name()
                        : "RELEASED:" + botEventId;
        if (key.length() > KEY_LIMIT) {
            throw new IllegalStateException("reservation event key is too long: " + key);
        }
        return key;
    }

    /** Digest of the creation request, which is where the private request fingerprint ends up. */
    static String createdHash(ReservationOpening opening) {
        ResourceReservation reservation = opening.reservation();
        ReservationPolicyPins pins = opening.pins();
        ReservationPricing pricing = opening.pricing();
        MessageDigest digest = digest();
        write(digest, "reservation-created:v1");
        write(digest, reservation.intentId().toString());
        write(digest, reservation.reservationKey());
        write(digest, opening.scope().botId().toString());
        write(digest, opening.scope().partitionId().toString());
        write(digest, opening.flowId().toString());
        write(digest, opening.createdEventId().toString());
        write(digest, decimal(reservation.reserved()));
        write(digest, reservation.createdAt().toString());
        write(digest, text(pins.bufferPolicyId()));
        write(digest, text(pins.feePolicyId()));
        write(digest, text(pins.shortRiskPolicyId()));
        write(digest, pins.precisionRulesVersion());
        write(digest, decimal(pricing.referencePrice()));
        write(digest, text(pricing.referenceObservedAt()));
        write(digest, text(pricing.referenceMarketHash()));
        write(digest, decimal(pricing.baseNotional()));
        write(digest, decimal(pricing.fixedSlippageAmount()));
        write(digest, decimal(pricing.estimatedFeeAmount()));
        write(digest, decimal(pricing.bufferAmount()));
        for (LotReservationAllocation allocation : reservation.lotAllocations()) {
            write(digest, allocation.lotId().toString());
            write(digest, decimal(allocation.reservedQuantity()));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Digest of a change request, taken before the change is applied. */
    static String commandHash(
            ReservationEventType eventType,
            UUID reservationId,
            UUID botEventId,
            UUID sourceFillId,
            BigDecimal measure,
            Instant occurredAt) {
        MessageDigest digest = digest();
        write(digest, "reservation-command:v1");
        write(digest, eventType.name());
        write(digest, reservationId.toString());
        write(digest, botEventId.toString());
        write(digest, text(sourceFillId));
        write(digest, decimal(measure));
        write(digest, occurredAt.toString());
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String decimal(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static void write(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }
}
