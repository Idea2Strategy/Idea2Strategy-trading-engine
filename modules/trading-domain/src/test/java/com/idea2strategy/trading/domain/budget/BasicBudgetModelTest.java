package com.idea2strategy.trading.domain.budget;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BasicBudgetModelTest {

    @Test
    void rejectsAValueRatioAboveOne() {
        assertThrows(IllegalArgumentException.class,
                () -> BasicSizingPolicy.availableBudgetRatio(new BigDecimal("1.01")));
    }

    @Test
    void rejectsCostPolicyRatesOutsideZeroToOne() {
        assertThrows(IllegalArgumentException.class,
                () -> new ExpectedCostPolicy("virtual-fill-cost-v1", new BigDecimal("-0.01"), BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new ExpectedCostPolicy("virtual-fill-cost-v1", BigDecimal.ZERO, new BigDecimal("-0.01")));
        assertThrows(IllegalArgumentException.class,
                () -> new ExpectedCostPolicy("virtual-fill-cost-v1", new BigDecimal("1.01"), BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new ExpectedCostPolicy("virtual-fill-cost-v1", BigDecimal.ZERO, new BigDecimal("1.01")));
    }

    @Test
    void rejectsBlankCostPolicyVersion() {
        assertThrows(IllegalArgumentException.class,
                () -> new ExpectedCostPolicy(" ", BigDecimal.ZERO, BigDecimal.ZERO));
    }

    @Test
    void rejectsNegativeCashPositionValueAndReservation() {
        assertThrows(IllegalArgumentException.class, () -> new BasicBudgetAllocationRequest(
                new BigDecimal("10000"), new BigDecimal("-1"), BigDecimal.ZERO,
                new ExpectedCostPolicy("virtual-fill-cost-v1", BigDecimal.ZERO, BigDecimal.ZERO), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new BasicBudgetAllocationRequest(
                new BigDecimal("10000"), BigDecimal.ZERO, new BigDecimal("-1"),
                new ExpectedCostPolicy("virtual-fill-cost-v1", BigDecimal.ZERO, BigDecimal.ZERO), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new BasicStrategyBudgetRequest(
                UUID.fromString("20000000-0000-0000-0000-000000000002"), new BigDecimal("0.50"),
                new BigDecimal("-1"), BigDecimal.ZERO, true,
                BasicSizingPolicy.fixedAmount(new BigDecimal("1000")), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new BasicStrategyBudgetRequest(
                UUID.fromString("20000000-0000-0000-0000-000000000002"), new BigDecimal("0.50"),
                BigDecimal.ZERO, new BigDecimal("-1"), true,
                BasicSizingPolicy.fixedAmount(new BigDecimal("1000")), List.of()));
    }

    @Test
    void rejectsDuplicateCandidateIdsWithinAStrategy() {
        UUID duplicate = UUID.fromString("10000000-0000-0000-0000-000000000001");
        assertThrows(IllegalArgumentException.class, () -> new BasicStrategyBudgetRequest(
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                new BigDecimal("0.50"), BigDecimal.ZERO, BigDecimal.ZERO, true,
                BasicSizingPolicy.fixedAmount(new BigDecimal("1000")),
                List.of(duplicate, duplicate)));
    }

    @Test
    void rejectsDuplicateStrategyIdsWithinARequest() {
        BasicStrategyBudgetRequest strategy = validStrategy();
        assertThrows(IllegalArgumentException.class, () -> new BasicBudgetAllocationRequest(
                new BigDecimal("10000"), new BigDecimal("8000"), BigDecimal.ZERO,
                new ExpectedCostPolicy("virtual-fill-cost-v1", new BigDecimal("0.002"), new BigDecimal("0.0005")),
                List.of(strategy, strategy)));
    }

    private static BasicStrategyBudgetRequest validStrategy() {
        return new BasicStrategyBudgetRequest(
                UUID.fromString("30000000-0000-0000-0000-000000000003"),
                new BigDecimal("0.50"), BigDecimal.ZERO, BigDecimal.ZERO, true,
                BasicSizingPolicy.fixedAmount(new BigDecimal("1000")),
                List.of(UUID.fromString("40000000-0000-0000-0000-000000000004")));
    }
}
