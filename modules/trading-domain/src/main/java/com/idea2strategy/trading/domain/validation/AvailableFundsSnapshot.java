package com.idea2strategy.trading.domain.validation;

import java.math.BigDecimal;

public record AvailableFundsSnapshot(String version, BigDecimal availableCash) {

    public AvailableFundsSnapshot {
        version = OrderValidityInputValidation.requireNonBlank(version, "version");
        availableCash = OrderValidityInputValidation.requireNonNegative(availableCash, "availableCash");
    }
}
