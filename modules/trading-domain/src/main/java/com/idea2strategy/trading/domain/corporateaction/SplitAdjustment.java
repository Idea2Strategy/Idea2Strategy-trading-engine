package com.idea2strategy.trading.domain.corporateaction;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record SplitAdjustment(BigDecimal beforeQuantity, BigDecimal afterQuantity,
                              BigDecimal preservedCostBasis, BigDecimal adjustedUnitCost) {
    public static SplitAdjustment exact(BigDecimal quantity,BigDecimal costBasis,long numerator,long denominator){
        if(quantity==null||costBasis==null||quantity.signum()<=0||costBasis.signum()<0)throw new IllegalArgumentException("lot projection is invalid");
        BigDecimal after=quantity.multiply(BigDecimal.valueOf(numerator))
                .divide(BigDecimal.valueOf(denominator),18,RoundingMode.UNNECESSARY).stripTrailingZeros();
        BigDecimal unit=costBasis.signum()==0?BigDecimal.ZERO:costBasis.divide(after,18,RoundingMode.HALF_EVEN).stripTrailingZeros();
        return new SplitAdjustment(quantity.stripTrailingZeros(),after,costBasis.stripTrailingZeros(),unit);
    }
}
