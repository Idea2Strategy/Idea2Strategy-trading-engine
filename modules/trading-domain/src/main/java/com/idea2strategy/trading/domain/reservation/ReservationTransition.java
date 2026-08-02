package com.idea2strategy.trading.domain.reservation;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What one change to a reservation produced: the reservation afterwards, and the canonical event
 * that has to be appended for the change to be storable at all.
 *
 * <p>Canonical does not let a reservation projection move on its own.
 * {@code assert_reservation_event_totals} sums the event deltas at commit and compares them with the
 * projection, so the domain hands both out together rather than letting the store invent the event.
 */
public record ReservationTransition(
        ResourceReservation reservation,
        ReservationEventType eventType,
        BigDecimal consumedDelta,
        BigDecimal releasedDelta,
        Instant occurredAt) {

    public ReservationTransition {
        ReservationValues.required(reservation, "reservation");
        ReservationValues.required(eventType, "eventType");
        consumedDelta = ReservationValues.nonNegative(consumedDelta, "consumedDelta");
        releasedDelta = ReservationValues.nonNegative(releasedDelta, "releasedDelta");
        ReservationValues.required(occurredAt, "occurredAt");
    }

    /** Canonical {@code reservation_events.reservation_sequence}. */
    public long sequence() {
        return reservation.lastEventSequence();
    }

    /** Canonical {@code reservation_events.status_after}. */
    public ReservationStatus statusAfter() {
        return reservation.status();
    }
}
