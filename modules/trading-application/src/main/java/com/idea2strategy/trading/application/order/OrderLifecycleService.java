package com.idea2strategy.trading.application.order;

import com.idea2strategy.trading.application.port.OrderLifecycleStore;
import com.idea2strategy.trading.domain.order.OrderComponent;
import com.idea2strategy.trading.domain.order.OrderLifecycle;
import com.idea2strategy.trading.domain.order.OrderLifecycleFactory;
import com.idea2strategy.trading.domain.order.OrderPlacement;
import com.idea2strategy.trading.domain.order.OrderPolicyPins;
import com.idea2strategy.trading.domain.order.OrderScope;
import com.idea2strategy.trading.domain.order.OrderTerms;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class OrderLifecycleService {
    private final OrderLifecycleFactory factory;
    private final OrderLifecycleStore store;

    public OrderLifecycleService(OrderLifecycleFactory factory, OrderLifecycleStore store) {
        this.factory = required(factory, "factory");
        this.store = required(store, "store");
    }

    public OrderLifecycle createAccepted(
            OrderTerms terms,
            Instant createdAt,
            OrderScope scope,
            OrderPolicyPins pins,
            UUID acceptedEventId) {
        return create(factory.accepted(terms, createdAt), scope, pins, acceptedEventId);
    }

    public OrderLifecycle createRejected(
            OrderTerms terms,
            Instant createdAt,
            String reason,
            OrderScope scope,
            OrderPolicyPins pins,
            UUID acceptedEventId) {
        return create(factory.rejected(terms, createdAt, reason), scope, pins, acceptedEventId);
    }

    /**
     * A single-intent order is the only composition this service builds. Netting several intents
     * into one order is a Pro-mode concern that no current caller reaches, and inventing a split
     * here would put an unapproved composition in the canonical attribution.
     */
    private OrderLifecycle create(
            OrderLifecycle lifecycle, OrderScope scope, OrderPolicyPins pins, UUID acceptedEventId) {
        OrderComponent whole = new OrderComponent(
                lifecycle.terms().intentId(), lifecycle.terms().quantity(), 1);
        return persisted(store.createOrLoad(
                new OrderPlacement(lifecycle, scope, pins, acceptedEventId, List.of(whole))));
    }

    public OrderLifecycle applyFill(FillOrderCommand command) {
        return apply(command);
    }

    public OrderLifecycle cancel(CancelOrderCommand command) {
        return apply(command);
    }

    public OrderLifecycle expire(ExpireOrderCommand command) {
        return apply(command);
    }

    private OrderLifecycle apply(OrderLifecycleCommand command) {
        return persisted(store.apply(required(command, "command")));
    }

    private static OrderLifecycle persisted(OrderLifecycle result) {
        return required(result, "store result");
    }

    private static <T> T required(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }
}
