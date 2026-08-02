package com.idea2strategy.trading.domain.execution;

public enum FillEligibility {
    ELIGIBLE,
    TRIGGER_NOT_REACHED,
    PRICE_LIMIT_NOT_MARKETABLE,
    NO_OBSERVED_LIQUIDITY
}
