package com.idea2strategy.trading.domain.position;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idea2strategy.trading.domain.order.OrderScope;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PositionLotTest {

    private static final OrderScope SCOPE = new OrderScope(UUID.randomUUID(), UUID.randomUUID());
    private static final UUID FLOW = UUID.randomUUID();
    private static final UUID INSTRUMENT = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-08-02T14:30:00Z");

    @Test
    void theOpeningFeeIsCapitalisedIntoTheBasisAndAFractionalCloseRealisesTheNetResult() {
        PositionLot opened = opening("1.5", "150", "0.30").lot(1);
        LotClose close = opened.close(
                UUID.randomUUID(), new BigDecimal("0.5"), new BigDecimal("55"),
                new BigDecimal("0.11"), T0.plusSeconds(1), 2);

        assertAll(
                () -> assertDecimal("150.30", opened.openedCostBasisAmount()),
                () -> assertDecimal("100.20", opened.unitCost()),
                () -> assertDecimal("50.10", close.costBasisReleased()),
                () -> assertDecimal("54.89", close.netProceeds()),
                () -> assertDecimal("4.79", close.realizedPnl()),
                () -> assertDecimal("1", close.remainingLot().remainingQuantity()),
                () -> assertDecimal("100.20", close.remainingLot().remainingCostBasisAmount()),
                () -> assertEquals(2, close.remainingLot().lastEventSequence()));
    }

    @Test
    void aFinalCloseReleasesTheExactRemainingBasisWithoutRoundingResidue() {
        PositionLot opened = opening("3", "30", "0.01").lot(1);
        LotClose first = opened.close(
                UUID.randomUUID(), BigDecimal.ONE, new BigDecimal("11"), new BigDecimal("0.01"),
                T0.plusSeconds(1), 2);
        LotClose last = first.remainingLot().close(
                UUID.randomUUID(), new BigDecimal("2"), new BigDecimal("24"), new BigDecimal("0.02"),
                T0.plusSeconds(2), 3);

        assertAll(
                () -> assertDecimal("0", last.remainingLot().remainingQuantity()),
                () -> assertDecimal("0", last.remainingLot().remainingCostBasisAmount()),
                () -> assertEquals(Optional.of(T0.plusSeconds(2)), last.remainingLot().closedAt()),
                () -> assertDecimal(
                        opened.openedCostBasisAmount().toPlainString(),
                        first.costBasisReleased().add(last.costBasisReleased())),
                () -> assertThrows(IllegalStateException.class, () -> last.remainingLot().close(
                        UUID.randomUUID(), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO,
                        T0.plusSeconds(3), 4)));
    }

    /**
     * Canonical stores {@code numeric(24,8)} and PostgreSQL returns the column scale, so a figure
     * that only fits after rounding would compare unequal to what is read back.
     */
    @Test
    void anAmountThatDoesNotFitTheCanonicalScaleIsRefusedRatherThanRounded() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                new LotOpening(SCOPE, FLOW, INSTRUMENT, UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID(), LotSide.LONG, BigDecimal.ONE,
                        new BigDecimal("10.000000005"), BigDecimal.ZERO, T0));

        assertTrue(failure.getMessage().contains("canonical scale"));
    }

    /**
     * A lot is named by the allocation that bought it, which is what makes a redelivered fill reach
     * the row it already wrote instead of opening a second lot.
     */
    @Test
    void theLotIdentityFollowsTheOpeningFillAllocation() {
        UUID allocation = UUID.randomUUID();
        LotOpening first = new LotOpening(SCOPE, FLOW, INSTRUMENT, UUID.randomUUID(), allocation,
                UUID.randomUUID(), LotSide.LONG, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, T0);
        LotOpening again = new LotOpening(SCOPE, FLOW, INSTRUMENT, UUID.randomUUID(), allocation,
                UUID.randomUUID(), LotSide.LONG, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, T0);

        assertAll(
                () -> assertEquals(first.lotId(), again.lotId()),
                () -> assertEquals("OPEN_LONG", LotSide.LONG.openingPositionEffect()),
                () -> assertEquals("CLOSE_LONG", LotSide.LONG.closingPositionEffect()));
    }

    /** The last lot consumed takes the remainder, so the shares add back up to the allocation. */
    @Test
    void aClosingAllocationSplitsAcrossLotsWithoutLosingOrInventingProceeds() {
        LotClosing closing = new LotClosing(SCOPE, FLOW, INSTRUMENT, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), LotSide.LONG, new BigDecimal("3"),
                new BigDecimal("100"), new BigDecimal("0.20"), T0);

        BigDecimal firstShare = closing.share(closing.grossAmount(), BigDecimal.ONE);
        BigDecimal remainder = closing.grossAmount().subtract(firstShare);

        assertAll(
                () -> assertDecimal("33.33333333", firstShare),
                () -> assertDecimal("66.66666667", remainder),
                () -> assertDecimal("99.80", closing.netProceeds()));
    }

    private static LotOpening opening(String quantity, String gross, String fee) {
        return new LotOpening(SCOPE, FLOW, INSTRUMENT, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), LotSide.LONG, new BigDecimal(quantity), new BigDecimal(gross),
                new BigDecimal(fee), T0);
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "expected " + expected + " but was " + actual);
    }
}
