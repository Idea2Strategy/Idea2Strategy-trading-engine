package com.idea2strategy.trading.application.reservation;

import java.time.Instant;
import java.util.UUID;

/**
 * A change to a reservation that already exists.
 *
 * <p>There is one command per canonical {@code reservation_event_type} family and no more, because
 * a change canonical has no event type for cannot be recorded. In particular there is no resize:
 * canonical has no event type for a change of reserved size and no delta column for one, and every
 * release event leaves the reservation terminal, so a reservation cannot be grown or shrunk. A
 * reservation that no longer fits is released with
 * {@code ReservationReleaseCause.REPLACEMENT} and a new one is created against the replacement
 * intent.
 *
 * <p>The private {@code commandId} is gone. Every canonical reservation event names the official bot
 * event that caused it and lands under a key that is unique per reservation, which is what the
 * private command-receipt table was for.
 */
public sealed interface ReservationCommand
        permits ConsumeReservationCommand, ReleaseReservationCommand, SettleReservationCommand {

    UUID reservationId();

    /** The {@code last_event_sequence} the caller believes the reservation is at. */
    long expectedSequence();

    /** Canonical {@code reservation_events.bot_event_id}. */
    UUID botEventId();

    Instant occurredAt();
}
