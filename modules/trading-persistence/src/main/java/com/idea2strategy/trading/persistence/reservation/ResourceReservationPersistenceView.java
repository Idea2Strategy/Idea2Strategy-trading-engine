package com.idea2strategy.trading.persistence.reservation;

import com.idea2strategy.trading.domain.reservation.ReservationResourceType;
import com.idea2strategy.trading.domain.reservation.ReservationStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * A canonical {@code trading.resource_reservations} row exactly as it is stored.
 *
 * <p>Deliberately not the domain object: the point of reading it is to prove what actually reached
 * canonical, including the pins and the sizing terms the domain does not carry.
 */
public record ResourceReservationPersistenceView(
        UUID reservationId,
        String reservationKey,
        UUID botId,
        UUID partitionId,
        UUID flowId,
        UUID intentId,
        ReservationResourceType resourceType,
        String currencyCode,
        UUID instrumentId,
        UUID bufferPolicyId,
        UUID feePolicyId,
        UUID shortRiskPolicyId,
        String precisionRulesVersion,
        ReservationStatus status,
        BigDecimal referencePrice,
        Instant referenceObservedAt,
        String referenceMarketHash,
        BigDecimal baseNotional,
        BigDecimal fixedSlippageAmount,
        BigDecimal estimatedFeeAmount,
        BigDecimal bufferAmount,
        BigDecimal reservedAmount,
        BigDecimal consumedAmount,
        BigDecimal releasedAmount,
        BigDecimal reservedQuantity,
        BigDecimal consumedQuantity,
        BigDecimal releasedQuantity,
        UUID createdEventId,
        Instant createdAt,
        long lastEventSequence) {

    public Optional<UUID> buffer() {
        return Optional.ofNullable(bufferPolicyId);
    }

    public Optional<UUID> fee() {
        return Optional.ofNullable(feePolicyId);
    }

    public Optional<UUID> shortRisk() {
        return Optional.ofNullable(shortRiskPolicyId);
    }
}
