package com.idea2strategy.trading.domain.shorting;

import java.math.BigDecimal;

public enum DayCountConvention {
    ACTUAL_360(360), ACTUAL_365(365);

    private final BigDecimal daysPerYear;
    DayCountConvention(int daysPerYear) { this.daysPerYear = BigDecimal.valueOf(daysPerYear); }
    public BigDecimal daysPerYear() { return daysPerYear; }
}
