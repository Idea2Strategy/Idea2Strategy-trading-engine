package com.idea2strategy.trading.strategy.runtime.plan;

import java.util.Locale;

final class PlanValueValidation {
    private PlanValueValidation() {
    }

    static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    static String requireSha256(String value, String field) {
        String normalized = requireText(value, field).toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a SHA-256 hex digest");
        }
        return normalized;
    }
}
