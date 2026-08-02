package com.idea2strategy.trading.domain.reservation;

/**
 * Canonical {@code trading.reservation_event_type}.
 *
 * <p>Every change a reservation can undergo has to be one of these, because
 * {@code assert_reservation_event_totals} rebuilds the reservation's consumed and released totals
 * from the events and refuses a projection the events do not explain.
 *
 * <p>There is deliberately no member for a change of reserved size. See
 * {@link ReservationReleaseCause#REPLACEMENT}.
 */
public enum ReservationEventType {
    CREATED,
    CONSUMED_BY_FILL,
    SETTLED_BY_FILL,
    RELEASED_BY_CANCEL,
    RELEASED_BY_EXPIRY,
    RELEASED_BY_REJECTION,
    RELEASED_BY_REPLACEMENT;

    /** Whether canonical requires {@code source_fill_id} on this event and forbids it otherwise. */
    public boolean requiresSourceFill() {
        return this == CONSUMED_BY_FILL || this == SETTLED_BY_FILL;
    }
}
