package com.idea2strategy.trading.domain.position;

import com.idea2strategy.trading.domain.order.OrderScope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A FIFO close, as the canonical model records it.
 *
 * <p>One closing fill allocation can consume several lots, and canonical writes one
 * {@code lot_movements} row per lot consumed. Every one of those rows points back at this single
 * allocation, which is what {@code assert_close_allocation_capacity} uses to refuse a close that
 * moves more quantity than the fill actually settled.
 *
 * <p>The proceeds are the allocation's own economics rather than a price the caller re-derives.
 * Splitting a fixed gross amount and fee across the consumed lots is what keeps the movements
 * adding back up to the fill, which recomputing per-lot from a price would not.
 */
public record LotClosing(
        OrderScope scope,
        UUID flowId,
        UUID instrumentId,
        UUID closingOrderComponentId,
        UUID closingFillAllocationId,
        UUID botEventId,
        LotSide lotSide,
        BigDecimal quantity,
        BigDecimal grossAmount,
        BigDecimal feeAmount,
        Instant occurredAt) {

    public LotClosing {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(flowId, "flowId");
        Objects.requireNonNull(instrumentId, "instrumentId");
        Objects.requireNonNull(closingOrderComponentId, "closingOrderComponentId");
        Objects.requireNonNull(closingFillAllocationId, "closingFillAllocationId");
        Objects.requireNonNull(botEventId, "botEventId");
        Objects.requireNonNull(lotSide, "lotSide");
        Objects.requireNonNull(occurredAt, "occurredAt");
        quantity = PositionLot.positive(quantity, "quantity");
        grossAmount = PositionLot.positive(grossAmount, "grossAmount");
        feeAmount = PositionLot.nonNegative(feeAmount, "feeAmount");
    }

    /** Canonical {@code lot_movements.id} of the CLOSE movement against one lot. */
    public UUID movementId(UUID positionLotId) {
        return PositionLotIdentity.movementId(
                Objects.requireNonNull(positionLotId, "positionLotId"), botEventId);
    }

    /** What the whole close is worth after its fee. */
    public BigDecimal netProceeds() {
        return grossAmount.subtract(feeAmount);
    }

    /**
     * The share of an allocation-wide amount that belongs to {@code take} units.
     *
     * <p>Rounded, so the caller has to give the last lot consumed whatever remains instead of a
     * second rounded share. Only then do the per-lot figures sum to the allocation exactly.
     */
    public BigDecimal share(BigDecimal amount, BigDecimal take) {
        return PositionLot.exact(amount, "amount")
                .multiply(PositionLot.positive(take, "take"))
                .divide(quantity, PositionLot.SCALE, RoundingMode.HALF_EVEN);
    }
}
