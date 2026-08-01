package com.idea2strategy.trading.strategy.runtime.basic;

final class BasicValueValidation {
    private BasicValueValidation() {}

    static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
