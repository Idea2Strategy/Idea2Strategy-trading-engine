package com.idea2strategy.trading.application.reservation;

import java.time.Instant;
import java.util.UUID;

public record ReleaseReservationCommand(UUID commandId, UUID reservationId, long expectedVersion,
                                        Instant occurredAt, String reason) implements ReservationCommand {
    public ReleaseReservationCommand {
        if (commandId == null || reservationId == null || occurredAt == null || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("release command values must not be null or blank");
        }
        if (expectedVersion < 1) {
            throw new IllegalArgumentException("expectedVersion must be positive");
        }
    }
}
