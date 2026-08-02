package com.idea2strategy.trading.domain.corporateaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SplitAdjustmentTest {

    @Test
    void splitChangesQuantityAndUnitCostWhilePreservingTotalBasis() {
        SplitAdjustment adjustment =
                SplitAdjustment.exact(new BigDecimal("1.5"), new BigDecimal("150.30"), 2, 1);

        assertEquals(0, new BigDecimal("3").compareTo(adjustment.afterQuantity()));
        assertEquals(0, new BigDecimal("150.30").compareTo(adjustment.preservedCostBasis()));
        assertEquals(0, new BigDecimal("50.10").compareTo(adjustment.adjustedUnitCost()));
    }

    /** Canonical records the change, and only the change, so it is derived rather than assumed. */
    @Test
    void reportsTheQuantityDeltaCanonicalStores() {
        SplitAdjustment forward =
                SplitAdjustment.exact(new BigDecimal("1.5"), new BigDecimal("150.30"), 2, 1);
        SplitAdjustment reverse =
                SplitAdjustment.exact(new BigDecimal("8"), new BigDecimal("100"), 1, 4);

        assertEquals(0, new BigDecimal("1.5").compareTo(forward.quantityDelta()));
        assertEquals(0, new BigDecimal("-6").compareTo(reverse.quantityDelta()));
    }

    @Test
    void refusesAnUnrepresentableQuantityInsteadOfHiddenRounding() {
        assertThrows(ArithmeticException.class,
                () -> SplitAdjustment.exact(BigDecimal.ONE, BigDecimal.TEN, 1, 3));
    }

    /**
     * Nine decimals fit the private {@code numeric(38,18)} column and do not fit canonical
     * {@code numeric(28,8)}, so what used to be storable is now refused at the domain boundary
     * rather than rounded into a position nobody can reconcile.
     */
    @Test
    void refusesAQuantityCanonicalCannotHoldEvenThoughThePrivateSchemaCould() {
        assertThrows(ArithmeticException.class,
                () -> SplitAdjustment.exact(new BigDecimal("0.00000001"), BigDecimal.TEN, 1, 10));
    }
}
