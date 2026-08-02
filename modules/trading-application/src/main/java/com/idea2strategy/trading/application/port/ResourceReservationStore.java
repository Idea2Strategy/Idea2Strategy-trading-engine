package com.idea2strategy.trading.application.port;

import com.idea2strategy.trading.application.reservation.ReservationCommand;
import com.idea2strategy.trading.domain.reservation.ResourceReservation;

public interface ResourceReservationStore {
    ResourceReservation createOrLoad(ResourceReservation desired);
    ResourceReservation apply(ReservationCommand command);
}
