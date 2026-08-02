package com.idea2strategy.trading.domain.eligibility;

public enum OrderEligibilityReason {
    FRACTIONAL_INSTRUMENT_NOT_ENABLED,
    FRACTIONAL_REQUIRES_LONG_EXPOSURE,
    FRACTIONAL_REQUIRES_MARKET_DAY,
    WHOLE_SHARES_REQUIRE_INTEGER_QUANTITY
}
