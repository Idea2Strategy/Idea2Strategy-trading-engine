package com.idea2strategy.trading.domain.reservation;

import com.idea2strategy.trading.domain.order.OrderScope;
import java.util.Objects;
import java.util.UUID;

/**
 * The moment an approved intent's reservation is attached to the order component that was actually
 * composed from it, as canonical {@code trading.order_component_reservations} records it.
 *
 * <p>This link is not decoration. {@code assert_fill_reservation_consumption} reaches from a fill
 * consumption event to the {@code fill_component_allocations} row that justifies it <em>through</em>
 * this table, so a reservation that was never linked to its component cannot be consumed by a fill
 * at all.
 *
 * <p>{@code reservation_id} is the canonical primary key, so one reservation backs exactly one
 * component. The reserved measure is not carried here: it is the reservation's own, and copying it
 * would only create somewhere for the two to disagree.
 */
public record ReservationComponentLink(
        OrderScope scope, UUID reservationId, UUID orderComponentId) {

    public ReservationComponentLink {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(orderComponentId, "orderComponentId");
    }
}
