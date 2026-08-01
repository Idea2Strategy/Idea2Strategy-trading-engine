package com.idea2strategy.trading.application.intent;

import com.idea2strategy.trading.application.port.OrderIntentBatchStore;
import com.idea2strategy.trading.domain.intent.OrderIntentBatch;
import com.idea2strategy.trading.domain.intent.OrderIntentBatchFactory;
import com.idea2strategy.trading.domain.intent.OrderIntentBatchRequest;
import java.util.Objects;

public final class OrderIntentBatchService {
    private final OrderIntentBatchFactory factory;
    private final OrderIntentBatchStore store;

    public OrderIntentBatchService(OrderIntentBatchFactory factory, OrderIntentBatchStore store) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.store = Objects.requireNonNull(store, "store");
    }

    public OrderIntentBatch createOrLoad(OrderIntentBatchRequest request) {
        OrderIntentBatch desired = factory.create(Objects.requireNonNull(request, "request"));
        return Objects.requireNonNull(store.createOrLoad(desired), "store result");
    }
}
