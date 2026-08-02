package com.idea2strategy.trading.application.reservation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ConsumeReservationCommand(UUID commandId, UUID reservationId, long expectedVersion,
                                        BigDecimal amount, Instant occurredAt) implements ReservationCommand {
    public ConsumeReservationCommand {
        if (commandId == null || reservationId == null || amount == null || occurredAt == null) {
            throw new IllegalArgumentException("reservation command values must not be null");
        }
        if (expectedVersion < 1 || amount.signum() <= 0) {
            throw new IllegalArgumentException("expectedVersion and amount must be positive");
        }
        amount = amount.stripTrailingZeros();
    }
}
