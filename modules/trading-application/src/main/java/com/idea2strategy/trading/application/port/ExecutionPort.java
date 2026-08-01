package com.idea2strategy.trading.application.port;

import com.idea2strategy.trading.domain.execution.Execution;
import com.idea2strategy.trading.domain.order.Order;

public interface ExecutionPort {
    Execution execute(Order order);
}
