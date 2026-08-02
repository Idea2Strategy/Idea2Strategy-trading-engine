package com.idea2strategy.trading.application.reservation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The fill that finishes the order settles the reservation, producing canonical
 * {@code SETTLED_BY_FILL}.
 *
 * <p>The one event both consumes what the fill actually used and releases the remainder — the
 * buying-power buffer and whatever the fee estimate overshot by — because
 * {@code reservation_amount_final_conservation} refuses a settled reservation whose consumed and
 * released totals do not add back up to what it reserved.
 *
 * <p>Whether a fill is the last one is the caller's knowledge, not something the amounts reveal, so
 * it is a separate command rather than a flag on {@link ConsumeReservationCommand}.
 */
public record SettleReservationCommand(
        UUID reservationId,
        long expectedSequence,
        UUID botEventId,
        UUID sourceFillId,
        BigDecimal consumed,
        Instant occurredAt) implements ReservationCommand {

    public SettleReservationCommand {
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(botEventId, "botEventId");
        Objects.requireNonNull(sourceFillId, "sourceFillId");
        Objects.requireNonNull(consumed, "consumed");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (expectedSequence < 1) {
            throw new IllegalArgumentException("expectedSequence must be positive");
        }
        if (consumed.signum() <= 0) {
            throw new IllegalArgumentException("consumed must be positive");
        }
    }
}
