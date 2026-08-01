package com.idea2strategy.trading.strategy.runtime.incremental;

import java.util.Objects;

final class IncrementalValueValidation {
    private IncrementalValueValidation() {
    }

    static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
