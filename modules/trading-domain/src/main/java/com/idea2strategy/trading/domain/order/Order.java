package com.idea2strategy.trading.domain.order;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public record Order(
        UUID orderId,
        UUID candidateId,
        UUID instrumentId,
        String side,
        BigDecimal quantity,
        BigDecimal limitPrice) {

    public Order {
        orderId = Objects.requireNonNull(orderId, "orderId");
        candidateId = Objects.requireNonNull(candidateId, "candidateId");
        instrumentId = Objects.requireNonNull(instrumentId, "instrumentId");
        side = Objects.requireNonNull(side, "side");
        quantity = Objects.requireNonNull(quantity, "quantity");
    }
}
