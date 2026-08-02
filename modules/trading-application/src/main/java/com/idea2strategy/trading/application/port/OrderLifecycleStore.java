package com.idea2strategy.trading.application.port;

import com.idea2strategy.trading.application.order.OrderLifecycleCommand;
import com.idea2strategy.trading.domain.order.OrderLifecycle;
import com.idea2strategy.trading.domain.order.OrderPlacement;

public interface OrderLifecycleStore {

    /**
     * Records an accepted or rejected order.
     *
     * <p>Takes a placement rather than the lifecycle alone because the canonical order row cannot be
     * written without its partition, the intents that composed it, the event that accepted it and
     * the platform rules it is pinned to.
     */
    OrderLifecycle createOrLoad(OrderPlacement placement);

    OrderLifecycle apply(OrderLifecycleCommand command);
}
