package com.idea2strategy.trading.domain.corporateaction;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * What a split does to one lot: the quantity changes, the cost basis does not.
 *
 * <p>Every figure is fixed at the canonical scale of eight decimals on the way in. The private
 * schema kept eighteen, so a ratio needing more than eight used to be storable; canonical cannot
 * hold it, and it is now refused outright rather than rounded into a position nobody can reconcile.
 */
public record SplitAdjustment(BigDecimal beforeQuantity, BigDecimal afterQuantity,
                              BigDecimal preservedCostBasis, BigDecimal adjustedUnitCost) {

    /** Canonical {@code numeric(28,8)} quantities and {@code numeric(24,8)} amounts. */
    public static final int SCALE = 8;

    public static SplitAdjustment exact(BigDecimal quantity, BigDecimal costBasis,
                                        long numerator, long denominator) {
        if (quantity == null || costBasis == null || quantity.signum() <= 0
                || costBasis.signum() < 0) {
            throw new IllegalArgumentException("lot projection is invalid");
        }
        BigDecimal after = quantity.multiply(BigDecimal.valueOf(numerator))
                .divide(BigDecimal.valueOf(denominator), SCALE, RoundingMode.UNNECESSARY);
        BigDecimal unit = costBasis.signum() == 0
                ? BigDecimal.ZERO.setScale(SCALE)
                : costBasis.divide(after, SCALE, RoundingMode.HALF_EVEN);
        return new SplitAdjustment(quantity.setScale(SCALE, RoundingMode.UNNECESSARY), after,
                costBasis.setScale(SCALE, RoundingMode.UNNECESSARY), unit);
    }

    /**
     * Canonical {@code lot_movements.quantity_delta}, which records the change rather than the new
     * total. {@code lot_movement_quantity_nonzero} refuses a change of nothing.
     */
    public BigDecimal quantityDelta() {
        return afterQuantity.subtract(beforeQuantity);
    }
}
