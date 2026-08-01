package com.idea2strategy.trading.application.port;

import com.idea2strategy.trading.application.order.OrderLifecycleCommand;
import com.idea2strategy.trading.domain.order.OrderLifecycle;

public interface OrderLifecycleStore {
    OrderLifecycle createOrLoad(OrderLifecycle desired);

    OrderLifecycle apply(OrderLifecycleCommand command);
}
