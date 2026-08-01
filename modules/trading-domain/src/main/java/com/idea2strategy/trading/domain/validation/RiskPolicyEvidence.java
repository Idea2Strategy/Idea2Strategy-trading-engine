package com.idea2strategy.trading.domain.validation;

public record RiskPolicyEvidence(String metricCode, String policyVersion) {

    public RiskPolicyEvidence {
        metricCode = OrderValidityInputValidation.requireNonBlank(metricCode, "metricCode");
        policyVersion = OrderValidityInputValidation.requireNonBlank(policyVersion, "policyVersion");
    }
}
