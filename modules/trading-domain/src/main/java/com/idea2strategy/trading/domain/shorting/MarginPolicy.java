package com.idea2strategy.trading.domain.shorting;

import java.math.BigDecimal;

public record MarginPolicy(
        String policyVersion,
        BigDecimal initialMarginRate,
        BigDecimal maintenanceMarginRate) {
    public MarginPolicy {
        policyVersion = ShortInputs.text(policyVersion, "policyVersion");
        initialMarginRate = ShortInputs.positive(initialMarginRate, "initialMarginRate");
        maintenanceMarginRate = ShortInputs.positive(maintenanceMarginRate, "maintenanceMarginRate");
    }
}
