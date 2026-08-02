package com.idea2strategy.trading.application.position;

import java.math.BigDecimal;
import java.util.UUID;

public record PositionMutationResult(UUID fillRecordId, BigDecimal quantityDelta, BigDecimal costBasisDelta,
                                     BigDecimal realizedPnl, BigDecimal endingQuantity,
                                     BigDecimal endingCostBasis, int affectedLots) {}
