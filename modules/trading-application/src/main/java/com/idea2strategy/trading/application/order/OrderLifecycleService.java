package com.idea2strategy.trading.application.order;

import com.idea2strategy.trading.application.port.OrderLifecycleStore;
import com.idea2strategy.trading.domain.order.OrderLifecycle;
import com.idea2strategy.trading.domain.order.OrderLifecycleFactory;
import com.idea2strategy.trading.domain.order.OrderTerms;
import java.time.Instant;

public final class OrderLifecycleService {
    private final OrderLifecycleFactory factory;
    private final OrderLifecycleStore store;

    public OrderLifecycleService(OrderLifecycleFactory factory, OrderLifecycleStore store) {
        this.factory = required(factory, "factory");
        this.store = required(store, "store");
    }

    public OrderLifecycle createAccepted(OrderTerms terms, Instant createdAt) {
        return persisted(store.createOrLoad(factory.accepted(terms, createdAt)));
    }

    public OrderLifecycle createRejected(OrderTerms terms, Instant createdAt, String reason) {
        return persisted(store.createOrLoad(factory.rejected(terms, createdAt, reason)));
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
