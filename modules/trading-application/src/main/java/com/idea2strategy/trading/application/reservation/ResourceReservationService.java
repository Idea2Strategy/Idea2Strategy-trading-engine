package com.idea2strategy.trading.application.reservation;

import com.idea2strategy.trading.application.port.ResourceReservationStore;
import com.idea2strategy.trading.domain.reservation.ResourceReservation;

public final class ResourceReservationService {
    private final ResourceReservationStore store;

    public ResourceReservationService(ResourceReservationStore store) {
        if (store == null) throw new IllegalArgumentException("store must not be null");
        this.store = store;
    }

    public ResourceReservation reserve(ResourceReservation desired) {
        if (desired == null) throw new IllegalArgumentException("desired must not be null");
        return store.createOrLoad(desired);
    }

    public ResourceReservation consume(ConsumeReservationCommand command) { return store.apply(command); }
    public ResourceReservation resize(ResizeReservationCommand command) { return store.apply(command); }
    public ResourceReservation release(ReleaseReservationCommand command) { return store.apply(command); }
}
