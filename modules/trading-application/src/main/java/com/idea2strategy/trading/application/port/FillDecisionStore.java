package com.idea2strategy.trading.application.port;

import com.idea2strategy.trading.domain.execution.FillDecision;

@FunctionalInterface
public interface FillDecisionStore {
    FillDecision createOrLoad(FillDecision decision);
}
