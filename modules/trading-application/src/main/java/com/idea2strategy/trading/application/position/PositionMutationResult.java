package com.idea2strategy.trading.application.position;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What one fill allocation did to a flow's position.
 *
 * <p>Canonical splits a flow position into a long and a short side, so the ending state reports both
 * rather than one signed quantity. Realised profit is not kept per movement in canonical; it is
 * derived here from the allocation's proceeds and the cost basis the movements released, which is
 * what a ledger posting in the same transaction needs.
 */
public record PositionMutationResult(
        UUID fillAllocationId,
        BigDecimal quantityDelta,
        BigDecimal costBasisDelta,
        BigDecimal realizedPnl,
        BigDecimal endingLongQuantity,
        BigDecimal endingShortQuantity,
        BigDecimal endingCostBasisAmount,
        int affectedLots) {
}
