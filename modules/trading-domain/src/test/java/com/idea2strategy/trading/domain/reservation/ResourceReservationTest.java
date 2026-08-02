package com.idea2strategy.trading.domain.reservation;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The reservation state machine, held to the shapes canonical will accept.
 *
 * <p>Every rule asserted here has a matching CHECK constraint or deferred trigger on
 * {@code trading.resource_reservations} and {@code trading.reservation_events}. Enforcing them in
 * the domain is what turns a database error at commit into a readable failure where it was caused.
 */
class ResourceReservationTest {

    private static final UUID INTENT = UUID.fromString("29000000-0000-4000-8000-000000000001");
    private static final UUID INSTRUMENT = UUID.fromString("26000000-0000-4000-8000-000000000001");
    private static final Instant T0 = Instant.parse("2026-08-02T00:00:00Z");

    @Test
    void aReservationIsIdentifiedByItsIntentAndResourceRatherThanByAnOrder() {
        ResourceReservation cash = ResourceReservation.cash(INTENT, "usd", amount("100"), T0);
        ResourceReservation quantity = ResourceReservation.positionQuantity(
                INTENT, INSTRUMENT, amount("3"), List.of(lot(INSTRUMENT, "3")), T0);

        assertAll(
                () -> assertEquals("CASH_BUYING_POWER:USD", cash.reservationKey()),
                () -> assertEquals("POSITION_QUANTITY:" + INSTRUMENT, quantity.reservationKey()),
                () -> assertEquals(cash.reservationId(),
                        ResourceReservation.cash(INTENT, "USD", amount("999"), T0).reservationId(),
                        "the id follows the intent and the resource, not the size"),
                () -> assertTrue(!cash.reservationId().equals(quantity.reservationId())),
                () -> assertEquals(ResourceReservation.CREATED_SEQUENCE, cash.lastEventSequence()));
    }

    /** Mirrors the per-resource-type evidence CHECKs on {@code resource_reservations}. */
    @Test
    void everyResourceTypeCarriesExactlyTheEvidenceCanonicalDemands() {
        ResourceReservation collateral =
                ResourceReservation.shortCollateral(INTENT, "USD", INSTRUMENT, amount("50"), T0);

        assertAll(
                () -> assertEquals("SHORT_COLLATERAL_CASH:USD:" + INSTRUMENT,
                        collateral.reservationKey()),
                () -> assertTrue(collateral.measuredInAmount()),
                () -> assertThrows(IllegalArgumentException.class, () -> new ResourceReservation(
                        null, INTENT, ReservationResourceType.CASH_BUYING_POWER, "USD", INSTRUMENT,
                        amount("1"), zero(), zero(), ReservationStatus.ACTIVE, null, 1, T0, T0,
                        List.of()), "buying power names no instrument"),
                () -> assertThrows(IllegalArgumentException.class, () -> new ResourceReservation(
                        null, INTENT, ReservationResourceType.POSITION_QUANTITY, "USD", INSTRUMENT,
                        amount("1"), zero(), zero(), ReservationStatus.ACTIVE, null, 1, T0, T0,
                        List.of(lot(INSTRUMENT, "1"))), "a quantity reservation names no currency"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> ResourceReservation.positionQuantity(
                                INTENT, INSTRUMENT, amount("3"), List.of(lot(INSTRUMENT, "2")), T0),
                        "the locked lots have to add up to the reserved quantity"));
    }

    @Test
    void aPartialFillConsumesTheReservationAndLeavesItActive() {
        ResourceReservation reserved = ResourceReservation.cash(INTENT, "USD", amount("100"), T0);

        ReservationTransition transition = reserved.consumeByFill(amount("35.25"), T0.plusSeconds(1));

        assertAll(
                () -> assertEquals(ReservationEventType.CONSUMED_BY_FILL, transition.eventType()),
                () -> assertEquals(ReservationStatus.ACTIVE, transition.statusAfter()),
                () -> assertEquals(2, transition.sequence()),
                () -> assertEquals(amount("35.25"), transition.consumedDelta()),
                () -> assertEquals(zero(), transition.releasedDelta(),
                        "active_reservation_not_released keeps the released total at zero"),
                () -> assertEquals(amount("64.75"), transition.reservation().remaining()));
    }

    /**
     * {@code partial_fill_consumption_stays_active} forces {@code status_after} to {@code ACTIVE},
     * so the fill that exhausts the reservation has to settle it instead.
     */
    @Test
    void aConsumptionThatWouldExhaustTheReservationHasToSettleItInstead() {
        ResourceReservation reserved = ResourceReservation.cash(INTENT, "USD", amount("100"), T0);

        assertThrows(IllegalArgumentException.class,
                () -> reserved.consumeByFill(amount("100"), T0.plusSeconds(1)));
    }

    @Test
    void theFinalFillConsumesWhatItUsedAndHandsBackTheBufferOnOneEvent() {
        ResourceReservation reserved = ResourceReservation.cash(INTENT, "USD", amount("20.20"), T0);
        ResourceReservation partly =
                reserved.consumeByFill(amount("10.02"), T0.plusSeconds(1)).reservation();

        ReservationTransition transition = partly.settleByFill(amount("10.02"), T0.plusSeconds(2));

        assertAll(
                () -> assertEquals(ReservationEventType.SETTLED_BY_FILL, transition.eventType()),
                () -> assertEquals(ReservationStatus.SETTLED, transition.statusAfter()),
                () -> assertEquals(amount("10.02"), transition.consumedDelta()),
                () -> assertEquals(amount("0.16"), transition.releasedDelta(),
                        "the buffer and the fee estimate error go back on the settling event"),
                () -> assertEquals(zero(), transition.reservation().remaining()),
                () -> assertEquals(3, transition.sequence()));
    }

    /**
     * {@code released_reservation_has_no_consumption} keeps {@code RELEASED} for a reservation
     * nothing drew on. A release after a partial fill is a settlement.
     */
    @Test
    void aReleaseEndsReleasedOnlyWhenNothingWasEverConsumed() {
        ResourceReservation untouched = ResourceReservation.cash(INTENT, "USD", amount("100"), T0);
        ResourceReservation partly =
                untouched.consumeByFill(amount("30"), T0.plusSeconds(1)).reservation();

        ReservationTransition cancelled =
                untouched.release(ReservationReleaseCause.CANCEL, T0.plusSeconds(1));
        ReservationTransition afterFill =
                partly.release(ReservationReleaseCause.EXPIRY, T0.plusSeconds(2));

        assertAll(
                () -> assertEquals(ReservationStatus.RELEASED, cancelled.statusAfter()),
                () -> assertEquals(ReservationEventType.RELEASED_BY_CANCEL, cancelled.eventType()),
                () -> assertEquals(amount("100"), cancelled.releasedDelta()),
                () -> assertEquals(ReservationReleaseCause.CANCEL,
                        cancelled.reservation().cause().orElseThrow()),
                () -> assertEquals(ReservationStatus.SETTLED, afterFill.statusAfter()),
                () -> assertEquals(ReservationEventType.RELEASED_BY_EXPIRY, afterFill.eventType()),
                () -> assertEquals(amount("70"), afterFill.releasedDelta()));
    }

    @Test
    void aTerminalReservationRefusesEveryFurtherChange() {
        ResourceReservation released = ResourceReservation.cash(INTENT, "USD", amount("10"), T0)
                .release(ReservationReleaseCause.REJECTION, T0)
                .reservation();

        assertAll(
                () -> assertThrows(IllegalStateException.class,
                        () -> released.consumeByFill(amount("1"), T0)),
                () -> assertThrows(IllegalStateException.class,
                        () -> released.settleByFill(amount("1"), T0)),
                () -> assertThrows(IllegalStateException.class,
                        () -> released.release(ReservationReleaseCause.CANCEL, T0)));
    }

    /** Canonical stores eight decimals, so anything finer is refused rather than rounded away. */
    @Test
    void aMeasureFinerThanCanonicalStoresIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ResourceReservation.cash(
                INTENT, "USD", new BigDecimal("1.000000001"), T0));
    }

    @Test
    void anOpeningPinsThePoliciesItsResourceTypeNeedsAndNoOthers() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> ReservationPolicyPins.buyingPower(null, UUID.randomUUID(), "v1")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> ReservationPolicyPins.buyingPower(
                                        UUID.randomUUID(), UUID.randomUUID(), "v1")
                                .requireFits(ReservationResourceType.POSITION_QUANTITY),
                        "cash_policies_only_for_buying_power"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> ReservationPolicyPins.shortCollateral(UUID.randomUUID(), "v1")
                                .requireFits(ReservationResourceType.CASH_BUYING_POWER),
                        "short_policy_only_for_collateral"));
    }

    @Test
    void theSizingTermsOfACashReservationHaveToAddUpToWhatItReserved() {
        assertThrows(IllegalArgumentException.class, () -> ReservationPricing.buyingPower(
                        amount("10"), T0, "m".repeat(64), amount("20"), amount("0.01"),
                        amount("0.04"), amount("0.15"))
                .requireFits(ReservationResourceType.CASH_BUYING_POWER, amount("30")));
    }

    private static LotReservationAllocation lot(UUID lotId, String quantity) {
        return new LotReservationAllocation(lotId, T0, amount(quantity));
    }

    private static BigDecimal amount(String value) {
        return new BigDecimal(value).setScale(8);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(8);
    }
}
