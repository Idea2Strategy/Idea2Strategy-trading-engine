package com.idea2strategy.trading.application.port;

import com.idea2strategy.trading.domain.execution.Execution;
import com.idea2strategy.trading.domain.settlement.Settlement;

public interface SettlementPort {
    Settlement settle(Execution execution);
}
