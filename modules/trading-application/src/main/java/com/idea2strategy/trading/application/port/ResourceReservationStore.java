package com.idea2strategy.trading.application.port;

import com.idea2strategy.trading.application.reservation.ReservationCommand;
import com.idea2strategy.trading.domain.reservation.ReservationComponentLink;
import com.idea2strategy.trading.domain.reservation.ReservationOpening;
import com.idea2strategy.trading.domain.reservation.ResourceReservation;

public interface ResourceReservationStore {

    /** Creates the reservation against its intent, or returns the one already stored for it. */
    ResourceReservation createOrLoad(ReservationOpening opening);

    /**
     * Attaches an existing reservation to the order component composed from its intent, and returns
     * the reservation. A fill cannot draw on a reservation that is not attached.
     */
    ResourceReservation attachToOrderComponent(ReservationComponentLink link);

    /** Applies one change and appends the canonical event that explains it. */
    ResourceReservation apply(ReservationCommand command);
}
