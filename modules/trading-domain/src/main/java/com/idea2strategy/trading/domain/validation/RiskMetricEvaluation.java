package com.idea2strategy.trading.domain.validation;

import java.math.BigDecimal;

public record RiskMetricEvaluation(
        String metricCode,
        String policyVersion,
        BigDecimal currentValue,
        BigDecimal projectedValue,
        BigDecimal maximumAllowedValue) {

    public RiskMetricEvaluation {
        metricCode = OrderValidityInputValidation.requireNonBlank(metricCode, "metricCode");
        policyVersion = OrderValidityInputValidation.requireNonBlank(policyVersion, "policyVersion");
        currentValue = OrderValidityInputValidation.requireNonNegative(currentValue, "currentValue");
        projectedValue = OrderValidityInputValidation.requireNonNegative(projectedValue, "projectedValue");
        maximumAllowedValue = OrderValidityInputValidation.requireNonNegative(
                maximumAllowedValue, "maximumAllowedValue");
    }
}
