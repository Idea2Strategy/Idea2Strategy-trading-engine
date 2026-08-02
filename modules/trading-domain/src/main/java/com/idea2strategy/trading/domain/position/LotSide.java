package com.idea2strategy.trading.domain.position;

/**
 * Canonical {@code trading.lot_side}.
 *
 * <p>A lot is one-sided for its whole life. Canonical
 * {@code assert_position_lot_provenance} ties the side to the {@code position_effect} of the intent
 * the opening fill allocation came from, so a lot can never claim a direction its intent did not
 * approve.
 */
public enum LotSide {
    LONG,
    SHORT;

    /** Canonical {@code position_effect} an opening allocation must carry for this side. */
    public String openingPositionEffect() {
        return this == LONG ? "OPEN_LONG" : "OPEN_SHORT";
    }

    /** Canonical {@code position_effect} a closing allocation must carry for this side. */
    public String closingPositionEffect() {
        return this == LONG ? "CLOSE_LONG" : "CLOSE_SHORT";
    }
}
