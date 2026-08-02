package com.idea2strategy.trading.application.reservation;

import java.time.Instant;
import java.util.UUID;

public sealed interface ReservationCommand permits ConsumeReservationCommand, ReleaseReservationCommand,
        ResizeReservationCommand {
    UUID commandId();
    UUID reservationId();
    long expectedVersion();
    Instant occurredAt();
}
