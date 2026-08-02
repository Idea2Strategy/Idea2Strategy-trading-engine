package com.idea2strategy.trading.application.reservation;

import com.idea2strategy.trading.domain.reservation.LotReservationAllocation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ResizeReservationCommand(UUID commandId, UUID reservationId, long expectedVersion,
                                       BigDecimal targetReserved,
                                       List<LotReservationAllocation> targetLotAllocations,
                                       Instant occurredAt) implements ReservationCommand {
    public ResizeReservationCommand {
        if (commandId == null || reservationId == null || targetReserved == null || occurredAt == null) {
            throw new IllegalArgumentException("resize command values must not be null");
        }
        if (expectedVersion < 1 || targetReserved.signum() <= 0) {
            throw new IllegalArgumentException("expectedVersion and targetReserved must be positive");
        }
        targetReserved = targetReserved.stripTrailingZeros();
        targetLotAllocations = targetLotAllocations == null ? List.of() : List.copyOf(targetLotAllocations);
    }
}
