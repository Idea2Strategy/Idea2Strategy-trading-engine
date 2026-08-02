package com.idea2strategy.trading.domain.eligibility;

import java.util.UUID;

public record InstrumentFractionalPolicy(
        UUID instrumentId,
        boolean fractionalEnabled,
        String policyVersion) {

    public InstrumentFractionalPolicy {
        if (instrumentId == null) {
            throw new IllegalArgumentException("instrumentId must not be null");
        }
        if (policyVersion == null || policyVersion.isBlank()) {
            throw new IllegalArgumentException("policyVersion must not be blank");
        }
    }
}
