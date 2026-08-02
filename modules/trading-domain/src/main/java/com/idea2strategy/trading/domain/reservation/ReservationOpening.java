package com.idea2strategy.trading.domain.reservation;

import com.idea2strategy.trading.domain.order.OrderScope;
import java.util.Objects;
import java.util.UUID;

/**
 * A reservation being created, as the canonical model records it.
 *
 * <p>{@link ResourceReservation} stays the conserving state machine and is not widened. What
 * canonical needs on top of it is placement and evidence: the partition and flow the reservation
 * lives in, the official bot event that created it, the platform rules it is pinned to and the
 * quote and arithmetic it was sized from. Carrying those here leaves the proven conservation rules
 * untouched, exactly as {@code OrderPlacement}, {@code FillPosting} and {@code LotOpening} do.
 *
 * <p>The order is deliberately absent. A reservation is created when the intent is approved, before
 * the order exists; the order arrives later as {@link ReservationComponentLink}.
 */
public record ReservationOpening(
        ResourceReservation reservation,
        OrderScope scope,
        UUID flowId,
        UUID createdEventId,
        ReservationPolicyPins pins,
        ReservationPricing pricing) {

    public ReservationOpening {
        Objects.requireNonNull(reservation, "reservation");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(flowId, "flowId");
        Objects.requireNonNull(createdEventId, "createdEventId");
        Objects.requireNonNull(pins, "pins");
        Objects.requireNonNull(pricing, "pricing");
        if (reservation.lastEventSequence() != ResourceReservation.CREATED_SEQUENCE
                || reservation.status() != ReservationStatus.ACTIVE) {
            throw new IllegalArgumentException("only a reservation as created can be opened");
        }
        pins.requireFits(reservation.resourceType());
        pricing.requireFits(reservation.resourceType(), reservation.reserved());
    }

    /** The {@code CREATED} event this opening has to append for the reservation to be storable. */
    public ReservationTransition created() {
        return new ReservationTransition(
                reservation, ReservationEventType.CREATED, ReservationValues.zero(),
                ReservationValues.zero(), reservation.createdAt());
    }
}
