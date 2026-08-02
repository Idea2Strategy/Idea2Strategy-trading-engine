package com.idea2strategy.trading.domain.shorting;

import java.math.RoundingMode;

public record BorrowFeePolicy(
        String policyVersion,
        DayCountConvention dayCountConvention,
        int currencyScale,
        RoundingMode roundingMode,
        String currency) {
    public BorrowFeePolicy {
        policyVersion = ShortInputs.text(policyVersion, "policyVersion");
        dayCountConvention = ShortInputs.required(dayCountConvention, "dayCountConvention");
        if (currencyScale < 0 || currencyScale > 18) throw new IllegalArgumentException("currencyScale must be 0..18");
        roundingMode = ShortInputs.required(roundingMode, "roundingMode");
        currency = ShortInputs.text(currency, "currency").toUpperCase(java.util.Locale.ROOT);
    }
}
