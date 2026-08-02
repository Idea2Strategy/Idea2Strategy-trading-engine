package com.idea2strategy.trading.domain.shorting;

import java.math.BigDecimal;
import java.util.Objects;

final class ShortInputs {
    private ShortInputs() {}

    static <T> T required(T value, String name) {
        return Objects.requireNonNull(value, name + " must not be null");
    }

    static String text(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    static BigDecimal nonNegative(BigDecimal value, String name) {
        required(value, name);
        if (value.signum() < 0) throw new IllegalArgumentException(name + " must not be negative");
        return value;
    }

    static BigDecimal positive(BigDecimal value, String name) {
        required(value, name);
        if (value.signum() <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    static BigDecimal canonical(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        return normalized.scale() < 0 ? normalized.setScale(0) : normalized;
    }

    static boolean isInteger(BigDecimal value) {
        return value.stripTrailingZeros().scale() <= 0;
    }
}
