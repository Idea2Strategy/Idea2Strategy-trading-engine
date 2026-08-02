package com.idea2strategy.trading.domain.position;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * What one closing fill allocation took out of one lot.
 *
 * <p>Canonical {@code lot_movements} keeps the quantity and cost-basis deltas but not the realised
 * result, because realised profit is recorded once on the partition projection and in the ledger.
 * The figure is still derived here so the caller that posts the ledger in the same transaction does
 * not have to recompute it.
 */
public record LotClose(
        UUID closingFillAllocationId,
        UUID lotId,
        BigDecimal quantity,
        BigDecimal grossProceeds,
        BigDecimal feeAmount,
        BigDecimal netProceeds,
        BigDecimal costBasisReleased,
        BigDecimal realizedPnl,
        Instant occurredAt,
        PositionLot remainingLot) {
}
