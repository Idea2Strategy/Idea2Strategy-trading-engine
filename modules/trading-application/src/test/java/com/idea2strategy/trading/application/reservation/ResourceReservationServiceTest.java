package com.idea2strategy.trading.application.reservation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.idea2strategy.trading.application.port.ResourceReservationStore;
import com.idea2strategy.trading.domain.reservation.ResourceReservation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResourceReservationServiceTest {
    private static final Instant T0 = Instant.parse("2026-08-02T00:00:00Z");

    @Test
    void delegatesCreationConsumptionAndReleaseToTheAtomicStoreBoundary() {
        ResourceReservation initial = ResourceReservation.cash(UUID.randomUUID(), "USD", new BigDecimal("100"), T0);
        ResourceReservation consumed = initial.consume(new BigDecimal("30"), T0.plusSeconds(1));
        ResourceReservation resized = consumed.resize(new BigDecimal("80"), java.util.List.of(), T0.plusSeconds(2));
        ResourceReservation released = resized.releaseRemaining(T0.plusSeconds(3), "ORDER_CLOSED");
        StubStore store = new StubStore(initial, consumed, resized, released);
        ResourceReservationService service = new ResourceReservationService(store);

        assertSame(initial, service.reserve(initial));
        assertSame(consumed, service.consume(new ConsumeReservationCommand(
                UUID.randomUUID(), initial.reservationId(), 1, new BigDecimal("30"), T0.plusSeconds(1))));
        assertSame(resized, service.resize(new ResizeReservationCommand(UUID.randomUUID(), initial.reservationId(),
                2, new BigDecimal("80"), java.util.List.of(), T0.plusSeconds(2))));
        assertSame(released, service.release(new ReleaseReservationCommand(
                UUID.randomUUID(), initial.reservationId(), 3, T0.plusSeconds(3), "ORDER_CLOSED")));
        assertEquals(4, store.calls);
    }

    private static final class StubStore implements ResourceReservationStore {
        private final ResourceReservation initial;
        private final ResourceReservation consumed;
        private final ResourceReservation resized;
        private final ResourceReservation released;
        private int calls;
        private StubStore(ResourceReservation initial, ResourceReservation consumed,
                          ResourceReservation resized, ResourceReservation released) {
            this.initial = initial; this.consumed = consumed; this.resized = resized; this.released = released;
        }
        public ResourceReservation createOrLoad(ResourceReservation desired) { calls++; return initial; }
        public ResourceReservation apply(ReservationCommand command) {
            calls++;
            if (command instanceof ConsumeReservationCommand) return consumed;
            if (command instanceof ResizeReservationCommand) return resized;
            return released;
        }
    }
}
