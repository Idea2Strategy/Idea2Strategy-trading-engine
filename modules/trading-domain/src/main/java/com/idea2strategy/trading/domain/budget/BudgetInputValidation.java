package com.idea2strategy.trading.domain.budget;

import java.util.List;

final class BudgetInputValidation {

    private BudgetInputValidation() {
    }

    static <T> T requireNonNull(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }

    static <T> List<T> immutableList(List<T> values, String name) {
        requireNonNull(values, name);
        if (values.stream().anyMatch(value -> value == null)) {
            throw new IllegalArgumentException(name + " must not contain null elements");
        }
        return List.copyOf(values);
    }
}
