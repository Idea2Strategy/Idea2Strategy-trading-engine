package com.idea2strategy.trading.application.port;

import com.idea2strategy.trading.domain.execution.Execution;
import com.idea2strategy.trading.domain.order.Order;

public interface ExecutionPort {
    /** Implementations must make repeated execution of the same order ID idempotent. */
    Execution execute(Order order);
}
