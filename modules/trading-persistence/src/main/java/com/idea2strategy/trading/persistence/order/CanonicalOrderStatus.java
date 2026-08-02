package com.idea2strategy.trading.persistence.order;

import com.idea2strategy.trading.domain.order.OrderStatus;

/**
 * Translates the lifecycle status into the canonical {@code trading.order_status} label.
 *
 * <p>Canonical has no PARTIALLY_FILLED. A partially filled order is {@code OPEN} carrying a
 * non-zero {@code filled_quantity}, which is what {@code open_projection_has_remaining_quantity}
 * allows since the partial fill migration dropped the constraint that forbade it. Both ACCEPTED and
 * PARTIALLY_FILLED therefore map onto OPEN, and the fill progress lives in the projection rather
 * than in the status.
 */
final class CanonicalOrderStatus {

    private CanonicalOrderStatus() {
    }

    static String of(OrderStatus status) {
        return switch (status) {
            case ACCEPTED, PARTIALLY_FILLED -> "OPEN";
            case FILLED -> "FILLED";
            case CANCELLED -> "CANCELLED";
            case EXPIRED -> "EXPIRED";
            case REJECTED -> "REJECTED";
        };
    }

    /** The canonical event label for the transition that produced this status. */
    static String eventTypeFor(OrderStatus previous, OrderStatus next) {
        if (previous == null) {
            return next == OrderStatus.REJECTED ? "ORDER_REJECTED" : "ORDER_ACCEPTED";
        }
        return switch (next) {
            case PARTIALLY_FILLED -> "ORDER_PARTIALLY_FILLED";
            case FILLED -> "ORDER_FILLED";
            case CANCELLED -> "ORDER_CANCELLED";
            case EXPIRED -> "ORDER_EXPIRED";
            case ACCEPTED, REJECTED -> throw new IllegalArgumentException(
                    next + " cannot follow " + previous);
        };
    }
}
