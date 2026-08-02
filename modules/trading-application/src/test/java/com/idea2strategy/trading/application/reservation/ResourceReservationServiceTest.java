package com.idea2strategy.trading.application.reservation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.idea2strategy.trading.application.port.ResourceReservationStore;
import com.idea2strategy.trading.domain.order.OrderScope;
import com.idea2strategy.trading.domain.reservation.ReservationComponentLink;
import com.idea2strategy.trading.domain.reservation.ReservationOpening;
import com.idea2strategy.trading.domain.reservation.ReservationPolicyPins;
import com.idea2strategy.trading.domain.reservation.ReservationPricing;
import com.idea2strategy.trading.domain.reservation.ReservationReleaseCause;
import com.idea2strategy.trading.domain.reservation.ResourceReservation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResourceReservationServiceTest {

    private static final Instant T0 = Instant.parse("2026-08-02T00:00:00Z");
    private static final UUID INTENT = UUID.fromString("29000000-0000-4000-8000-000000000001");
    private static final OrderScope SCOPE = new OrderScope(UUID.randomUUID(), UUID.randomUUID());

    /**
     * The order the calls arrive in is the canonical one: a reservation is created against an
     * approved intent, attached to the component composed from it, then drawn on by fills.
     */
    @Test
    void drivesTheCanonicalReservationLifecycleThroughTheAtomicStoreBoundary() {
        ResourceReservation reserved =
                ResourceReservation.cash(INTENT, "USD", amount("20.20"), T0);
        ReservationOpening opening = new ReservationOpening(
                reserved, SCOPE, UUID.randomUUID(), UUID.randomUUID(),
                ReservationPolicyPins.buyingPower(
                        UUID.randomUUID(), UUID.randomUUID(), "precision-rules:v1"),
                ReservationPricing.buyingPower(
                        amount("10"), T0, "m".repeat(64), amount("20"), amount("0.01"),
                        amount("0.04"), amount("0.15")));
        RecordingStore store = new RecordingStore(reserved);
        ResourceReservationService service = new ResourceReservationService(store);

        UUID fillId = UUID.randomUUID();
        assertSame(reserved, service.reserve(opening));
        assertSame(reserved, service.attachToOrderComponent(
                new ReservationComponentLink(SCOPE, reserved.reservationId(), UUID.randomUUID())));
        assertSame(reserved, service.consume(new ConsumeReservationCommand(
                reserved.reservationId(), 1, UUID.randomUUID(), fillId, amount("10.02"),
                T0.plusSeconds(1))));
        assertSame(reserved, service.settle(new SettleReservationCommand(
                reserved.reservationId(), 2, UUID.randomUUID(), UUID.randomUUID(), amount("10.02"),
                T0.plusSeconds(2))));
        assertSame(reserved, service.release(new ReleaseReservationCommand(
                reserved.reservationId(), 3, UUID.randomUUID(), ReservationReleaseCause.CANCEL,
                T0.plusSeconds(3))));

        assertEquals(
                List.of("createOrLoad", "attachToOrderComponent", "ConsumeReservationCommand",
                        "SettleReservationCommand", "ReleaseReservationCommand"),
                store.calls);
    }

    private static BigDecimal amount(String value) {
        return new BigDecimal(value).setScale(8);
    }

    private static final class RecordingStore implements ResourceReservationStore {

        private final ResourceReservation reservation;
        private final List<String> calls = new ArrayList<>();

        private RecordingStore(ResourceReservation reservation) {
            this.reservation = reservation;
        }

        @Override
        public ResourceReservation createOrLoad(ReservationOpening opening) {
            calls.add("createOrLoad");
            return reservation;
        }

        @Override
        public ResourceReservation attachToOrderComponent(ReservationComponentLink link) {
            calls.add("attachToOrderComponent");
            return reservation;
        }

        @Override
        public ResourceReservation apply(ReservationCommand command) {
            calls.add(command.getClass().getSimpleName());
            return reservation;
        }
    }
}
