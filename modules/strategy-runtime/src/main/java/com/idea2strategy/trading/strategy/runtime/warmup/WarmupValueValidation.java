package com.idea2strategy.trading.strategy.runtime.warmup;

import java.util.Objects;
import java.util.regex.Pattern;

final class WarmupValueValidation {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    private WarmupValueValidation() {
    }

    static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    static String requireSha256(String value, String name) {
        value = requireText(value, name);
        if (!SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 digest");
        }
        return value;
    }
}
