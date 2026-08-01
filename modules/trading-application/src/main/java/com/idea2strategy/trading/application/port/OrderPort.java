package com.idea2strategy.trading.application.port;

import com.idea2strategy.trading.domain.candidate.CandidateOrder;
import com.idea2strategy.trading.domain.order.Order;

public interface OrderPort {
    Order place(CandidateOrder candidate);
}
