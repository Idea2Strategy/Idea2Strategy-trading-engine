package com.idea2strategy.trading.application.reservation;

import com.idea2strategy.trading.domain.reservation.ReservationReleaseCause;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The unused balance goes back, producing canonical {@code RELEASED_BY_CANCEL},
 * {@code RELEASED_BY_EXPIRY}, {@code RELEASED_BY_REJECTION} or {@code RELEASED_BY_REPLACEMENT}.
 *
 * <p>The private free-text reason is now {@link ReservationReleaseCause}, because canonical stores
 * the reason as the event type and has no other place for it.
 */
public record ReleaseReservationCommand(
        UUID reservationId,
        long expectedSequence,
        UUID botEventId,
        ReservationReleaseCause cause,
        Instant occurredAt) implements ReservationCommand {

    public ReleaseReservationCommand {
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(botEventId, "botEventId");
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (expectedSequence < 1) {
            throw new IllegalArgumentException("expectedSequence must be positive");
        }
    }
}
