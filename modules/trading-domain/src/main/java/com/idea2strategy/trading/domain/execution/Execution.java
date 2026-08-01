package com.idea2strategy.trading.domain.execution;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public record Execution(
        UUID executionId,
        UUID orderId,
        BigDecimal quantity,
        BigDecimal price) {

    public Execution {
        executionId = Objects.requireNonNull(executionId, "executionId");
        orderId = Objects.requireNonNull(orderId, "orderId");
        quantity = Objects.requireNonNull(quantity, "quantity");
        price = Objects.requireNonNull(price, "price");
    }
}
