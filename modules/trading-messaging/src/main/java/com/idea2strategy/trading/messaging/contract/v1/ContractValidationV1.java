package com.idea2strategy.trading.messaging.contract.v1;

import java.time.Instant;
import java.util.regex.Pattern;

public final class ContractValidationV1 {
    private static final Pattern CANONICAL_NON_NEGATIVE_DECIMAL = Pattern.compile(
        "(?:0|[1-9]\\d*)(?:\\.\\d*[1-9])?"
    );
    private static final Pattern CURRENCY_CODE = Pattern.compile("[A-Z]{3}");

    private ContractValidationV1() {
    }

    public static String requiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }

    public static <T> T required(T value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }

    public static long positiveVersion(long value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    public static Instant utcInstant(Instant value, String fieldName) {
        return required(value, fieldName);
    }

    public static String canonicalNonNegativeDecimal(String value, String fieldName) {
        requiredText(value, fieldName);
        if (!CANONICAL_NON_NEGATIVE_DECIMAL.matcher(value).matches()) {
            throw new IllegalArgumentException(fieldName + " must be a canonical non-negative decimal");
        }
        return value;
    }

    public static String currencyCode(String value, String fieldName) {
        requiredText(value, fieldName);
        if (!CURRENCY_CODE.matcher(value).matches()) {
            throw new IllegalArgumentException(fieldName + " must be an uppercase ISO 4217 currency code");
        }
        return value;
    }
}
