package com.idea2strategy.trading.domain.validation;

import java.math.BigDecimal;

public record InstrumentNumericPolicy(
        String version,
        BigDecimal minimumNotional,
        BigDecimal minimumQuantity,
        int maximumQuantityScale,
        int maximumPriceScale) {

    public InstrumentNumericPolicy {
        version = OrderValidityInputValidation.requireNonBlank(version, "version");
        minimumNotional = OrderValidityInputValidation.requireNonNegative(minimumNotional, "minimumNotional");
        minimumQuantity = OrderValidityInputValidation.requireNonNegative(minimumQuantity, "minimumQuantity");
        OrderValidityInputValidation.requireNonNegative(maximumQuantityScale, "maximumQuantityScale");
        OrderValidityInputValidation.requireNonNegative(maximumPriceScale, "maximumPriceScale");
    }
}
