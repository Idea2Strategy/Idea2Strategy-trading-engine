package com.idea2strategy.trading.worker.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PostgresPositionMetricSourceTest {

    @Test
    void averageEntryPriceUsesThePinnedHalfEvenPrecisionRule() {
        // 40.00000100 / 40 = 1.000000025, an exact tie at the ninth decimal.
        assertEquals(
                new BigDecimal("1.00000002"),
                PostgresPositionMetricSource.averageEntryPrice(
                        new BigDecimal("40.00000100"), new BigDecimal("40.00000000")));
    }
}
