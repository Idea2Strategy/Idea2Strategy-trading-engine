package com.idea2strategy.trading.domain.reservation;

/**
 * Why an unused reservation balance went back.
 *
 * <p>The private schema carried a free-text {@code terminal_reason}. Canonical has no such column:
 * the reason is the event type, and only these four exist. Narrowing the string to this enum is what
 * makes the private reason storable at all, and it is checked here rather than discovered when
 * {@code release_event_status_valid} rejects the write.
 *
 * <p>{@link #REPLACEMENT} is also how canonical expresses a change of reserved size. There is no
 * resize event type and no delta column for a change of {@code reserved_amount}, and every release
 * event must leave the reservation terminal, so a reservation cannot be grown or shrunk in place. A
 * reservation that no longer fits its intent is released as replaced and a new reservation is
 * created against the replacement intent, which is what
 * {@code order_component_reservations}' own contract already says happens for a replacement order.
 */
public enum ReservationReleaseCause {
    CANCEL(ReservationEventType.RELEASED_BY_CANCEL),
    EXPIRY(ReservationEventType.RELEASED_BY_EXPIRY),
    REJECTION(ReservationEventType.RELEASED_BY_REJECTION),
    REPLACEMENT(ReservationEventType.RELEASED_BY_REPLACEMENT);

    private final ReservationEventType eventType;

    ReservationReleaseCause(ReservationEventType eventType) {
        this.eventType = eventType;
    }

    public ReservationEventType eventType() {
        return eventType;
    }

    /** The cause behind a canonical release event type. */
    public static ReservationReleaseCause of(ReservationEventType eventType) {
        for (ReservationReleaseCause cause : values()) {
            if (cause.eventType == eventType) {
                return cause;
            }
        }
        throw new IllegalArgumentException(eventType + " is not a release event");
    }
}
