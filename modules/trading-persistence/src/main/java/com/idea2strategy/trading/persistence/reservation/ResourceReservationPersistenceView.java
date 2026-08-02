package com.idea2strategy.trading.persistence.reservation;

import com.idea2strategy.trading.domain.reservation.LotReservationAllocation;
import com.idea2strategy.trading.domain.reservation.ReservationResourceType;
import com.idea2strategy.trading.domain.reservation.ReservationStatus;
import com.idea2strategy.trading.domain.reservation.ResourceReservation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ResourceReservationPersistenceView(
        UUID reservationId, UUID createCommandId, String requestFingerprint, UUID orderId,
        ReservationResourceType resourceType, String resourceKey, BigDecimal reserved,
        BigDecimal consumed, BigDecimal released, ReservationStatus status, long version,
        Instant createdAt, Instant updatedAt, String terminalReason,
        List<LotReservationAllocation> lotAllocations) {
    public ResourceReservation toDomain() {
        return new ResourceReservation(reservationId, createCommandId, requestFingerprint, orderId, resourceType,
                resourceKey, reserved, consumed, released, status, version, createdAt, updatedAt,
                terminalReason, lotAllocations);
    }
}
