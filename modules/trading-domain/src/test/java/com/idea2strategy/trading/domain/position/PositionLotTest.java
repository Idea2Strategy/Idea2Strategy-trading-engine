package com.idea2strategy.trading.domain.position;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PositionLotTest {
    private static final Instant T0 = Instant.parse("2026-08-02T14:30:00Z");

    @Test
    void openingCommissionBelongsToCostBasisAndFractionalCloseRealizesNetPnl() {
        PositionLot opened = PositionLot.open(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("1.5"), new BigDecimal("100"),
                new BigDecimal("0.30"), T0);
        LotClose close = opened.close(UUID.randomUUID(), new BigDecimal("0.5"), new BigDecimal("110"),
                new BigDecimal("0.11"), T0.plusSeconds(1));

        assertEquals(0, opened.openedCostBasis().compareTo(new BigDecimal("150.30")));
        assertEquals(0, close.costBasisReleased().compareTo(new BigDecimal("50.10")));
        assertEquals(0, close.netProceeds().compareTo(new BigDecimal("54.89")));
        assertEquals(0, close.realizedPnl().compareTo(new BigDecimal("4.79")));
        assertEquals(0, close.remainingLot().remainingQuantity().compareTo(BigDecimal.ONE));
        assertEquals(0, close.remainingLot().remainingCostBasis().compareTo(new BigDecimal("100.20")));
    }

    @Test
    void finalCloseReleasesTheExactRemainingBasisWithoutRoundingResidue() {
        PositionLot opened = PositionLot.open(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("3"), new BigDecimal("10"),
                new BigDecimal("0.01"), T0);
        LotClose first = opened.close(UUID.randomUUID(), BigDecimal.ONE, new BigDecimal("11"),
                new BigDecimal("0.01"), T0.plusSeconds(1));
        LotClose last = first.remainingLot().close(UUID.randomUUID(), new BigDecimal("2"), new BigDecimal("12"),
                new BigDecimal("0.02"), T0.plusSeconds(2));

        assertEquals(0, last.remainingLot().remainingQuantity().compareTo(BigDecimal.ZERO));
        assertEquals(0, last.remainingLot().remainingCostBasis().compareTo(BigDecimal.ZERO));
        assertEquals(last.remainingLot().closedAt(), java.util.Optional.of(T0.plusSeconds(2)));
        assertEquals(0, first.costBasisReleased().add(last.costBasisReleased()).compareTo(opened.openedCostBasis()));
        assertThrows(IllegalStateException.class, () -> last.remainingLot().close(
                UUID.randomUUID(), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, T0.plusSeconds(3)));
    }
}
