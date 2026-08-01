package com.idea2strategy.trading.domain.validation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class OrderValidityInputValidation {

    private OrderValidityInputValidation() {
    }

    static <T> T requireNonNull(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }

    static String requireNonBlank(String value, String name) {
        requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    static BigDecimal requireNonNegative(BigDecimal value, String name) {
        requireNonNull(value, name);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    static BigDecimal requireSupportedDecimal(BigDecimal value, String name) {
        try {
            value.stripTrailingZeros();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + " has an unsupported decimal scale", exception);
        }
        return value;
    }

    static void requireSupportedProduct(BigDecimal left, BigDecimal right, String name) {
        try {
            left.multiply(right);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + " has an unsupported combined decimal scale", exception);
        }
    }

    static int normalizedScale(BigDecimal value) {
        try {
            return Math.max(0, value.stripTrailingZeros().scale());
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("value has an unsupported decimal scale", exception);
        }
    }

    static void requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }

    static <T> List<T> immutableList(List<T> values, String name) {
        requireNonNull(values, name);
        List<T> copy = new ArrayList<>(values.size());
        for (T value : values) {
            copy.add(requireNonNull(value, name + " element"));
        }
        return List.copyOf(copy);
    }

    static <T> void requireStrictlySorted(List<T> values, Comparator<? super T> comparator, String name) {
        for (int index = 1; index < values.size(); index++) {
            if (comparator.compare(values.get(index - 1), values.get(index)) >= 0) {
                throw new IllegalArgumentException(name + " must be sorted without duplicates");
            }
        }
    }
}
