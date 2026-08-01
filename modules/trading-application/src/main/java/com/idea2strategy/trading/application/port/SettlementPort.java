package com.idea2strategy.trading.application.port;

import com.idea2strategy.trading.domain.execution.Execution;
import com.idea2strategy.trading.domain.settlement.Settlement;

public interface SettlementPort {
    /** Implementations must make repeated settlement of the same execution ID idempotent. */
    Settlement settle(Execution execution);
}
