package com.idea2strategy.trading.domain.order;

import java.math.BigDecimal;
import java.time.Instant;

public final class OrderLifecycleFactory {

    public OrderLifecycle accepted(OrderTerms terms, Instant createdAt) {
        return create(terms, createdAt, OrderStatus.ACCEPTED, null);
    }

    public OrderLifecycle rejected(OrderTerms terms, Instant createdAt, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        return create(terms, createdAt, OrderStatus.REJECTED, reason);
    }

    private OrderLifecycle create(OrderTerms terms, Instant createdAt, OrderStatus initialStatus, String reason) {
        if (terms == null) {
            throw new IllegalArgumentException("terms must not be null");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt must not be null");
        }
        if (terms.timeInForce() == TimeInForce.GTD && !terms.expiresAt().isAfter(createdAt)) {
            throw new IllegalArgumentException("GTD expiresAt must be after createdAt");
        }
        var orderId = OrderLifecycleIdentity.orderId(terms);
        return new OrderLifecycle(
                orderId,
                OrderLifecycleIdentity.createCommandId(orderId),
                OrderLifecycleIdentity.requestFingerprint(terms, initialStatus, createdAt, reason),
                terms,
                initialStatus,
                BigDecimal.ZERO,
                1,
                createdAt,
                createdAt,
                reason);
    }
}
