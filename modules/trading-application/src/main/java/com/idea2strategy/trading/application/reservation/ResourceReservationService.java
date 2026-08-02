package com.idea2strategy.trading.application.reservation;

import com.idea2strategy.trading.application.port.ResourceReservationStore;
import com.idea2strategy.trading.domain.reservation.ReservationComponentLink;
import com.idea2strategy.trading.domain.reservation.ReservationOpening;
import com.idea2strategy.trading.domain.reservation.ResourceReservation;
import java.util.Objects;

/**
 * The reservation write path.
 *
 * <p>The order of the calls follows the canonical model rather than the private one: a reservation
 * is created against an approved intent, attached to an order component once the intent has been
 * composed into an order, and only then can be drawn on by a fill.
 */
public final class ResourceReservationService {

    private final ResourceReservationStore store;

    public ResourceReservationService(ResourceReservationStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /** Commits the resource to an approved intent. */
    public ResourceReservation reserve(ReservationOpening opening) {
        return store.createOrLoad(Objects.requireNonNull(opening, "opening"));
    }

    /** Attaches the reservation to the order component composed from its intent. */
    public ResourceReservation attachToOrderComponent(ReservationComponentLink link) {
        return store.attachToOrderComponent(Objects.requireNonNull(link, "link"));
    }

    /** One partial fill draws on the reservation and leaves it open. */
    public ResourceReservation consume(ConsumeReservationCommand command) {
        return store.apply(Objects.requireNonNull(command, "command"));
    }

    /** The final fill consumes what it used and hands the remainder back. */
    public ResourceReservation settle(SettleReservationCommand command) {
        return store.apply(Objects.requireNonNull(command, "command"));
    }

    /** The intent will not be filled, or not as reserved, so the balance goes back. */
    public ResourceReservation release(ReleaseReservationCommand command) {
        return store.apply(Objects.requireNonNull(command, "command"));
    }
}
