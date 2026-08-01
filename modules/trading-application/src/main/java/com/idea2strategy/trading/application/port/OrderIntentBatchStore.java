package com.idea2strategy.trading.application.port;

import com.idea2strategy.trading.domain.intent.OrderIntentBatch;

public interface OrderIntentBatchStore {
    OrderIntentBatch createOrLoad(OrderIntentBatch desired);
}
