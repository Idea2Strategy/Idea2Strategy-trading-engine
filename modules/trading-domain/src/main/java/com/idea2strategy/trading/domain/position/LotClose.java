package com.idea2strategy.trading.domain.position;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LotClose(UUID closingFillRecordId, UUID lotId, BigDecimal quantity, BigDecimal grossProceeds,
                       BigDecimal commission, BigDecimal netProceeds, BigDecimal costBasisReleased,
                       BigDecimal realizedPnl, Instant occurredAt, PositionLot remainingLot) {}
