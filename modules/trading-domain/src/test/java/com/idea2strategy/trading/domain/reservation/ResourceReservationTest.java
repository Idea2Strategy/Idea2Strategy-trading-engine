package com.idea2strategy.trading.domain.reservation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResourceReservationTest {
    private static final UUID ORDER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID INSTRUMENT_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final Instant T0 = Instant.parse("2026-08-01T14:30:00Z");
    private static final Instant T1 = Instant.parse("2026-08-01T14:31:00Z");
    private static final Instant T2 = Instant.parse("2026-08-01T14:32:00Z");
    private static final Instant T3 = Instant.parse("2026-08-01T14:33:00Z");

    @Test
    void cashReservationConservesReservedConsumedAndReleasedAmounts() {
        ResourceReservation created = ResourceReservation.cash(ORDER_ID, "USD", new BigDecimal("1000"), T0);

        ResourceReservation partial = created.consume(new BigDecimal("375.25"), T1);
        ResourceReservation settled = partial.releaseRemaining(T2, "ORDER_CANCELLED");

        assertEquals(ReservationStatus.ACTIVE, partial.status());
        assertEquals(0, partial.remaining().compareTo(new BigDecimal("624.75")));
        assertEquals(ReservationStatus.SETTLED, settled.status());
        assertEquals(0, settled.consumed().compareTo(new BigDecimal("375.25")));
        assertEquals(0, settled.released().compareTo(new BigDecimal("624.75")));
        assertEquals(0, settled.consumed().add(settled.released()).compareTo(settled.reserved()));
        assertEquals(3, settled.version());
    }

    @Test
    void activeCashReservationCanBeIncreasedOrDecreasedWhileKeepingConsumedValue() {
        ResourceReservation initial = ResourceReservation.cash(ORDER_ID, "USD", new BigDecimal("100"), T0);
        ResourceReservation consumed = initial.consume(new BigDecimal("20"), T1);
        ResourceReservation increased = consumed.resize(new BigDecimal("140"), List.of(), T2);
        ResourceReservation decreased = increased.resize(new BigDecimal("75"), List.of(), T3);

        assertEquals(0, decreased.remaining().compareTo(new BigDecimal("55")));
        assertEquals(0, decreased.consumed().compareTo(new BigDecimal("20")));
        assertThrows(IllegalArgumentException.class,
                () -> decreased.resize(new BigDecimal("20"), List.of(), T3));
    }

    @Test
    void unconsumedReservationBecomesReleasedAndCannotBeMutatedAgain() {
        ResourceReservation released = ResourceReservation.cash(ORDER_ID, "USD", new BigDecimal("10"), T0)
                .releaseRemaining(T1, "ORDER_EXPIRED");

        assertEquals(ReservationStatus.RELEASED, released.status());
        assertThrows(IllegalStateException.class, () -> released.consume(BigDecimal.ONE, T2));
        assertThrows(IllegalStateException.class, () -> released.releaseRemaining(T2, "RETRY"));
    }

    @Test
    void positionReservationConsumesLotsInFifoOrder() {
        UUID oldLot = UUID.fromString("30000000-0000-0000-0000-000000000001");
        UUID newLot = UUID.fromString("30000000-0000-0000-0000-000000000002");
        ResourceReservation created = ResourceReservation.position(
                ORDER_ID,
                INSTRUMENT_ID,
                new BigDecimal("3.5"),
                List.of(
                        new LotReservationAllocation(oldLot, T0.minusSeconds(60), new BigDecimal("2"), BigDecimal.ZERO, BigDecimal.ZERO),
                        new LotReservationAllocation(newLot, T0, new BigDecimal("1.5"), BigDecimal.ZERO, BigDecimal.ZERO)),
                T0);

        ResourceReservation partial = created.consume(new BigDecimal("2.25"), T1);

        assertEquals(List.of(new BigDecimal("2"), new BigDecimal("0.25")),
                partial.lotAllocations().stream().map(LotReservationAllocation::consumed).toList());
        assertEquals(0, partial.remaining().compareTo(new BigDecimal("1.25")));
    }

    @Test
    void rejectsOverConsumptionNonMonotonicTimeAndInvalidLotTotals() {
        ResourceReservation cash = ResourceReservation.cash(ORDER_ID, "USD", new BigDecimal("10"), T0);

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> cash.consume(new BigDecimal("10.01"), T1)).getMessage().contains("remaining"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> cash.consume(BigDecimal.ONE, T0.minusSeconds(1))).getMessage().contains("occurredAt"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ResourceReservation.position(
                        ORDER_ID,
                        INSTRUMENT_ID,
                        new BigDecimal("2"),
                        List.of(new LotReservationAllocation(UUID.randomUUID(), T0, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO)),
                        T0)).getMessage().contains("lot allocation total"));
    }

    @Test
    void identityAndCreationFingerprintAreDeterministicAndScaleInsensitive() {
        ResourceReservation first = ResourceReservation.cash(ORDER_ID, "usd", new BigDecimal("10.00"), T0);
        ResourceReservation retry = ResourceReservation.cash(ORDER_ID, "USD", new BigDecimal("10.0"), T0);

        assertEquals(first.reservationId(), retry.reservationId());
        assertEquals(first.createCommandId(), retry.createCommandId());
        assertEquals(first.requestFingerprint(), retry.requestFingerprint());
        assertEquals(5, first.reservationId().version());
        assertTrue(first.requestFingerprint().matches("[0-9a-f]{64}"));
    }
}
