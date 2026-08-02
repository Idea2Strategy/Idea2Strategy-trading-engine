package com.idea2strategy.trading.application.execution;

import com.idea2strategy.trading.application.port.FillDecisionStore;
import com.idea2strategy.trading.domain.execution.FillDecision;
import com.idea2strategy.trading.domain.execution.RealisticFillModel;
import com.idea2strategy.trading.domain.execution.RecordedMarketSnapshot;
import com.idea2strategy.trading.domain.order.OrderLifecycle;

public final class VirtualFillService {
    private final RealisticFillModel model;
    private final FillDecisionStore store;

    public VirtualFillService(RealisticFillModel model, FillDecisionStore store) {
        this.model = required(model, "model");
        this.store = required(store, "store");
    }

    public FillDecision evaluate(OrderLifecycle order, RecordedMarketSnapshot snapshot) {
        return required(store.createOrLoad(model.evaluate(required(order, "order"), required(snapshot, "snapshot"))),
                "store result");
    }

    private static <T> T required(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " must not be null");
        return value;
    }
}
