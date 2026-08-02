package com.idea2strategy.trading.domain.position;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record PositionLot(
        UUID lotId, UUID botId, UUID partitionId, UUID flowId, UUID instrumentId, UUID openingFillRecordId,
        BigDecimal openedQuantity, BigDecimal unitPrice, BigDecimal openingCommission,
        BigDecimal openedCostBasis, BigDecimal remainingQuantity, BigDecimal remainingCostBasis,
        Instant openedAt, Optional<Instant> closedAt, long version) {

    private static final int SCALE = 18;

    public PositionLot {
        lotId = required(lotId, "lotId"); botId = required(botId, "botId");
        partitionId = required(partitionId, "partitionId"); flowId = required(flowId, "flowId");
        instrumentId = required(instrumentId, "instrumentId");
        openingFillRecordId = required(openingFillRecordId, "openingFillRecordId");
        openedQuantity = positive(openedQuantity, "openedQuantity");
        unitPrice = positive(unitPrice, "unitPrice");
        openingCommission = nonNegative(openingCommission, "openingCommission");
        openedCostBasis = positive(openedCostBasis, "openedCostBasis");
        remainingQuantity = nonNegative(remainingQuantity, "remainingQuantity");
        remainingCostBasis = nonNegative(remainingCostBasis, "remainingCostBasis");
        openedAt = required(openedAt, "openedAt");
        closedAt = closedAt == null ? Optional.empty() : closedAt;
        if (version < 1 || remainingQuantity.compareTo(openedQuantity) > 0
                || remainingCostBasis.compareTo(openedCostBasis) > 0) {
            throw new IllegalArgumentException("invalid lot projection bounds");
        }
        if ((remainingQuantity.signum() == 0) != closedAt.isPresent()) {
            throw new IllegalArgumentException("closedAt must match zero remaining quantity");
        }
        if (closedAt.isPresent() && closedAt.orElseThrow().isBefore(openedAt)) {
            throw new IllegalArgumentException("closedAt precedes openedAt");
        }
        if (!PositionLotIdentity.lotId(openingFillRecordId).equals(lotId)) {
            throw new IllegalArgumentException("lotId does not match opening fill");
        }
    }

    public static PositionLot open(UUID botId, UUID partitionId, UUID flowId, UUID instrumentId,
                                   UUID openingFillRecordId, BigDecimal quantity, BigDecimal price,
                                   BigDecimal commission, Instant openedAt) {
        BigDecimal q = positive(quantity, "quantity");
        BigDecimal p = positive(price, "price");
        BigDecimal fee = nonNegative(commission, "commission");
        BigDecimal basis = q.multiply(p).add(fee).stripTrailingZeros();
        return new PositionLot(PositionLotIdentity.lotId(required(openingFillRecordId, "openingFillRecordId")),
                botId, partitionId, flowId, instrumentId, openingFillRecordId, q, p, fee, basis,
                q, basis, required(openedAt, "openedAt"), Optional.empty(), 1);
    }

    public LotClose close(UUID closingFillRecordId, BigDecimal quantity, BigDecimal salePrice,
                          BigDecimal allocatedCommission, Instant occurredAt) {
        if (closedAt.isPresent()) throw new IllegalStateException("lot is already closed");
        UUID fillId = required(closingFillRecordId, "closingFillRecordId");
        BigDecimal q = positive(quantity, "quantity");
        BigDecimal price = positive(salePrice, "salePrice");
        BigDecimal fee = nonNegative(allocatedCommission, "allocatedCommission");
        Instant at = required(occurredAt, "occurredAt");
        if (q.compareTo(remainingQuantity) > 0) throw new IllegalArgumentException("close exceeds remaining quantity");
        if (at.isBefore(openedAt)) throw new IllegalArgumentException("close precedes open");

        boolean finalClose = q.compareTo(remainingQuantity) == 0;
        BigDecimal basisReleased = finalClose ? remainingCostBasis
                : remainingCostBasis.multiply(q).divide(remainingQuantity, SCALE, RoundingMode.HALF_EVEN);
        BigDecimal gross = q.multiply(price);
        BigDecimal net = gross.subtract(fee);
        BigDecimal realized = net.subtract(basisReleased);
        BigDecimal nextQuantity = remainingQuantity.subtract(q).stripTrailingZeros();
        BigDecimal nextBasis = finalClose ? BigDecimal.ZERO
                : remainingCostBasis.subtract(basisReleased).stripTrailingZeros();
        PositionLot next = new PositionLot(lotId, botId, partitionId, flowId, instrumentId, openingFillRecordId,
                openedQuantity, unitPrice, openingCommission, openedCostBasis, nextQuantity, nextBasis,
                openedAt, finalClose ? Optional.of(at) : Optional.empty(), version + 1);
        return new LotClose(fillId, lotId, q, gross.stripTrailingZeros(), fee, net.stripTrailingZeros(),
                basisReleased.stripTrailingZeros(), realized.stripTrailingZeros(), at, next);
    }

    private static BigDecimal positive(BigDecimal value, String name) {
        BigDecimal normalized = nonNegative(value, name);
        if (normalized.signum() <= 0) throw new IllegalArgumentException(name + " must be positive");
        return normalized;
    }
    private static BigDecimal nonNegative(BigDecimal value, String name) {
        BigDecimal normalized = required(value, name).stripTrailingZeros();
        if (normalized.signum() < 0) throw new IllegalArgumentException(name + " must not be negative");
        if (Math.max(0, normalized.scale()) > SCALE) throw new IllegalArgumentException(name + " exceeds scale 18");
        return normalized;
    }
    private static <T> T required(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " must not be null");
        return value;
    }
}
