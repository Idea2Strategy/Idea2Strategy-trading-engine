package com.idea2strategy.trading.domain.reservation;

/**
 * Canonical {@code trading.reservation_resource_type}.
 *
 * <p>The private schema knew only cash and quantity. Canonical separates the cash a short sale must
 * post as collateral from ordinary buying power, because the two carry different evidence: buying
 * power is pinned to a buffer and a fee policy, collateral to a short-risk policy and the instrument
 * that was sold short.
 */
public enum ReservationResourceType {
    CASH_BUYING_POWER,
    POSITION_QUANTITY,
    SHORT_COLLATERAL_CASH;

    /** Whether the reservation is measured in money rather than in instrument quantity. */
    public boolean measuredInAmount() {
        return this != POSITION_QUANTITY;
    }
}
