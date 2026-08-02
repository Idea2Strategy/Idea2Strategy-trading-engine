package com.idea2strategy.trading.domain.corporateaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SplitAdjustmentTest {
    @Test void splitChangesQuantityAndUnitCostWhilePreservingTotalBasis(){
        SplitAdjustment a=SplitAdjustment.exact(new BigDecimal("1.5"),new BigDecimal("150.30"),2,1);
        assertEquals(0,new BigDecimal("3").compareTo(a.afterQuantity()));assertEquals(0,new BigDecimal("150.30").compareTo(a.preservedCostBasis()));assertEquals(0,new BigDecimal("50.10").compareTo(a.adjustedUnitCost()));
    }
    @Test void refusesAnUnrepresentableQuantityInsteadOfHiddenRounding(){
        assertThrows(ArithmeticException.class,()->SplitAdjustment.exact(BigDecimal.ONE,BigDecimal.TEN,1,3));
    }
}
