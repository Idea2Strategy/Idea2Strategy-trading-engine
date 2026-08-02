package com.idea2strategy.trading.domain.position;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * One immutable FIFO cost lot together with its rebuildable remainder.
 *
 * <p>Canonical splits this over two tables: {@code trading.position_lots} holds what the lot was
 * opened as and never changes, while {@code trading.position_lot_projections} holds what is left of
 * it. Keeping both in one record is what lets a close be computed and checked in memory before any
 * row moves.
 *
 * <p>Every amount is held at the canonical scale of 8 and refuses to be rounded into it. Canonical
 * stores {@code numeric(24,8)} and {@code numeric(28,8)}, and PostgreSQL returns values at the
 * column scale, so a lot that only fits after rounding would compare unequal to what was read back.
 * The two figures that genuinely cannot be exact — {@code unitCost} and the proportional basis a
 * partial close releases — are documented where they are derived.
 */
public record PositionLot(
        UUID lotId,
        UUID botId,
        UUID partitionId,
        UUID flowId,
        UUID instrumentId,
        UUID openingOrderComponentId,
        UUID openingFillAllocationId,
        LotSide lotSide,
        BigDecimal openedQuantity,
        BigDecimal unitCost,
        BigDecimal openedCostBasisAmount,
        BigDecimal remainingQuantity,
        BigDecimal remainingCostBasisAmount,
        Instant openedAt,
        Optional<Instant> closedAt,
        long lastEventSequence) {

    /** Canonical {@code numeric(28,8)} quantities and {@code numeric(24,8)} amounts. */
    static final int SCALE = 8;

    public PositionLot {
        lotId = required(lotId, "lotId");
        botId = required(botId, "botId");
        partitionId = required(partitionId, "partitionId");
        flowId = required(flowId, "flowId");
        instrumentId = required(instrumentId, "instrumentId");
        openingOrderComponentId = required(openingOrderComponentId, "openingOrderComponentId");
        openingFillAllocationId = required(openingFillAllocationId, "openingFillAllocationId");
        lotSide = required(lotSide, "lotSide");
        openedQuantity = positive(openedQuantity, "openedQuantity");
        unitCost = nonNegative(unitCost, "unitCost");
        openedCostBasisAmount = nonNegative(openedCostBasisAmount, "openedCostBasisAmount");
        remainingQuantity = nonNegative(remainingQuantity, "remainingQuantity");
        remainingCostBasisAmount = nonNegative(remainingCostBasisAmount, "remainingCostBasisAmount");
        openedAt = required(openedAt, "openedAt");
        closedAt = closedAt == null ? Optional.empty() : closedAt;

        if (remainingQuantity.compareTo(openedQuantity) > 0
                || remainingCostBasisAmount.compareTo(openedCostBasisAmount) > 0) {
            throw new IllegalArgumentException("a lot cannot hold more than it was opened with");
        }
        if (lastEventSequence < 1) {
            throw new IllegalArgumentException("lastEventSequence must be positive");
        }
        // Canonical lot_projection_closed_consistent.
        if ((remainingQuantity.signum() == 0) != closedAt.isPresent()) {
            throw new IllegalArgumentException("closedAt must match zero remaining quantity");
        }
        if (closedAt.isPresent() && closedAt.orElseThrow().isBefore(openedAt)) {
            throw new IllegalArgumentException("closedAt precedes openedAt");
        }
        if (!PositionLotIdentity.lotId(openingFillAllocationId).equals(lotId)) {
            throw new IllegalArgumentException("lotId does not match the opening fill allocation");
        }
    }

    /**
     * Consumes part of the lot for one closing fill allocation.
     *
     * <p>The proceeds are carried rather than recomputed from a price. Canonical already fixed the
     * gross amount and the fee of the closing allocation, and splitting those figures is the only
     * way the movements of a FIFO close can add back up to exactly what the fill settled.
     *
     * @param eventSequence the official event sequence that caused the close; canonical keeps it on
     *     the projection in place of the private optimistic-lock version column
     */
    public LotClose close(
            UUID closingFillAllocationId,
            BigDecimal quantity,
            BigDecimal grossProceeds,
            BigDecimal feeAmount,
            Instant occurredAt,
            long eventSequence) {
        if (closedAt.isPresent()) {
            throw new IllegalStateException("lot is already closed");
        }
        UUID allocation = required(closingFillAllocationId, "closingFillAllocationId");
        BigDecimal size = positive(quantity, "quantity");
        BigDecimal gross = positive(grossProceeds, "grossProceeds");
        BigDecimal fee = nonNegative(feeAmount, "feeAmount");
        Instant at = required(occurredAt, "occurredAt");
        if (size.compareTo(remainingQuantity) > 0) {
            throw new IllegalArgumentException("close exceeds remaining quantity");
        }
        if (at.isBefore(openedAt)) {
            throw new IllegalArgumentException("close precedes open");
        }
        if (eventSequence < lastEventSequence) {
            throw new IllegalArgumentException("a close cannot precede the lot's last event");
        }

        // A final close releases exactly what is left. Releasing a rounded share instead would
        // strand a residue that canonical's closed-consistency check then refuses.
        boolean finalClose = size.compareTo(remainingQuantity) == 0;
        BigDecimal basisReleased = finalClose
                ? remainingCostBasisAmount
                : remainingCostBasisAmount.multiply(size)
                        .divide(remainingQuantity, SCALE, RoundingMode.HALF_EVEN);
        BigDecimal net = gross.subtract(fee);
        BigDecimal realized = net.subtract(basisReleased);
        PositionLot next = new PositionLot(
                lotId, botId, partitionId, flowId, instrumentId, openingOrderComponentId,
                openingFillAllocationId, lotSide, openedQuantity, unitCost, openedCostBasisAmount,
                remainingQuantity.subtract(size), remainingCostBasisAmount.subtract(basisReleased),
                openedAt, finalClose ? Optional.of(at) : Optional.empty(), eventSequence);
        return new LotClose(
                allocation, lotId, size, gross, fee, net, basisReleased, realized, at, next);
    }

    /** Canonical {@code lot_movements.id} of the movement this event writes against the lot. */
    public UUID movementId(UUID botEventId) {
        return PositionLotIdentity.movementId(lotId, required(botEventId, "botEventId"));
    }

    static BigDecimal positive(BigDecimal value, String name) {
        BigDecimal normalized = nonNegative(value, name);
        if (normalized.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return normalized;
    }

    static BigDecimal nonNegative(BigDecimal value, String name) {
        BigDecimal normalized = exact(value, name);
        if (normalized.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return normalized;
    }

    static BigDecimal exact(BigDecimal value, String name) {
        required(value, name);
        try {
            return value.setScale(SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException notExactlyRepresentable) {
            throw new IllegalArgumentException(
                    name + " exceeds the canonical scale of " + SCALE, notExactlyRepresentable);
        }
    }

    static <T> T required(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }
}
