package com.idea2strategy.trading.persistence.projection;

import java.time.Instant;
import java.util.UUID;

public record TradingReasonView(
        UUID reasonId,
        UUID orderId,
        ReasonScopeLevel scopeLevel,
        ProjectionReasonType type,
        String code,
        String detail,
        Instant occurredAt) {}
