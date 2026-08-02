package com.idea2strategy.trading.domain.position;

import com.idea2strategy.trading.domain.order.OrderScope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A lot being opened, as the canonical model records it.
 *
 * <p>{@link PositionLot} stays the FIFO cost object and is not widened. What canonical needs on top
 * of it is provenance: the partition the lot lives in, the order component it belongs to, the exact
 * fill allocation that bought it and the official event that caused the movement. Carrying those
 * here leaves the proven cost and close rules untouched.
 *
 * <p>A lot is opened by a <em>fill allocation</em>, not by a fill. One partial fill against an order
 * composed of several intents settles several components at once, and each of those becomes its own
 * lot, which the private schema's single {@code opening_fill_record_id} could not express.
 */
public record LotOpening(
        OrderScope scope,
        UUID flowId,
        UUID instrumentId,
        UUID openingOrderComponentId,
        UUID openingFillAllocationId,
        UUID botEventId,
        LotSide lotSide,
        BigDecimal openedQuantity,
        BigDecimal grossAmount,
        BigDecimal feeAmount,
        Instant openedAt) {

    public LotOpening {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(flowId, "flowId");
        Objects.requireNonNull(instrumentId, "instrumentId");
        Objects.requireNonNull(openingOrderComponentId, "openingOrderComponentId");
        Objects.requireNonNull(openingFillAllocationId, "openingFillAllocationId");
        Objects.requireNonNull(botEventId, "botEventId");
        Objects.requireNonNull(lotSide, "lotSide");
        Objects.requireNonNull(openedAt, "openedAt");
        openedQuantity = PositionLot.positive(openedQuantity, "openedQuantity");
        grossAmount = PositionLot.positive(grossAmount, "grossAmount");
        feeAmount = PositionLot.nonNegative(feeAmount, "feeAmount");
    }

    /** Canonical {@code position_lots.id}, derived so a redelivered allocation reaches its lot. */
    public UUID lotId() {
        return PositionLotIdentity.lotId(openingFillAllocationId);
    }

    /** Canonical {@code lot_movements.id} of the OPEN movement. */
    public UUID openingMovementId() {
        return PositionLotIdentity.movementId(lotId(), botEventId);
    }

    /**
     * Canonical {@code opened_cost_basis_amount}. The fee belongs to what the position cost, so it
     * is capitalised into the basis rather than expensed away from it.
     */
    public BigDecimal openedCostBasisAmount() {
        return grossAmount.add(feeAmount);
    }

    /**
     * Canonical {@code unit_cost}: the all-in cost of one unit, which is where the private schema's
     * separate {@code unit_price} and {@code opening_commission} end up.
     *
     * <p>This is the one derived figure the write path is allowed to round. A fee charged in basis
     * points makes the per-unit cost longer than eight decimals for most sizes, and canonical does
     * not tie {@code unit_cost} to {@code opened_cost_basis_amount}: the basis stays authoritative
     * and the per-unit figure is the reportable view of it, exactly as
     * {@code partition_position_projections.average_cost} is.
     */
    public BigDecimal unitCost() {
        return openedCostBasisAmount()
                .divide(openedQuantity, PositionLot.SCALE, RoundingMode.HALF_EVEN);
    }

    /** The lot this opening produces, at the event sequence that caused it. */
    public PositionLot lot(long eventSequence) {
        return new PositionLot(
                lotId(), scope.botId(), scope.partitionId(), flowId, instrumentId,
                openingOrderComponentId, openingFillAllocationId, lotSide, openedQuantity,
                unitCost(), openedCostBasisAmount(), openedQuantity, openedCostBasisAmount(),
                openedAt, Optional.empty(), eventSequence);
    }
}
