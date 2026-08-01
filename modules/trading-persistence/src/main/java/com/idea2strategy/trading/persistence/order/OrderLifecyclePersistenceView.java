package com.idea2strategy.trading.persistence.order;

import com.idea2strategy.trading.domain.order.OrderLifecycle;
import com.idea2strategy.trading.domain.order.OrderSide;
import com.idea2strategy.trading.domain.order.OrderStatus;
import com.idea2strategy.trading.domain.order.OrderTerms;
import com.idea2strategy.trading.domain.order.OrderType;
import com.idea2strategy.trading.domain.order.TimeInForce;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderLifecyclePersistenceView(
        UUID orderId,
        UUID createCommandId,
        String requestFingerprint,
        UUID intentId,
        UUID candidateId,
        UUID instrumentId,
        String side,
        BigDecimal quantity,
        String orderType,
        String timeInForce,
        BigDecimal limitPrice,
        BigDecimal stopPrice,
        BigDecimal trailPercent,
        Instant expiresAt,
        String status,
        BigDecimal cumulativeFilledQuantity,
        long version,
        Instant createdAt,
        Instant lastTransitionAt,
        String terminalReason) {

    public OrderLifecycle toDomain() {
        OrderTerms terms = new OrderTerms(
                intentId,
                candidateId,
                instrumentId,
                OrderSide.valueOf(side),
                quantity,
                OrderType.valueOf(orderType),
                TimeInForce.valueOf(timeInForce),
                limitPrice,
                stopPrice,
                trailPercent,
                expiresAt);
        return new OrderLifecycle(
                orderId,
                createCommandId,
                requestFingerprint,
                terms,
                OrderStatus.valueOf(status),
                cumulativeFilledQuantity,
                version,
                createdAt,
                lastTransitionAt,
                terminalReason);
    }
}
