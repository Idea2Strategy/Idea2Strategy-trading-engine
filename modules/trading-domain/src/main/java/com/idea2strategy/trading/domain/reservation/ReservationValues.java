package com.idea2strategy.trading.domain.reservation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Canonical numeric and text shapes for the reservation tables.
 *
 * <p>Both reservation measures are stored at eight decimals — {@code numeric(24,8)} for money and
 * {@code numeric(28,8)} for quantity — so every value is fixed to that scale at the domain entrance
 * and a value that would need rounding is rejected rather than quietly rounded. Records compare
 * scale as well as magnitude and PostgreSQL always returns the column scale, so a reservation read
 * back has to equal the one that was written.
 */
final class ReservationValues {

    /** Canonical scale of {@code numeric(24,8)} amounts and {@code numeric(28,8)} quantities. */
    static final int SCALE = 8;

    private ReservationValues() {}

    static BigDecimal positive(BigDecimal value, String name) {
        BigDecimal exact = nonNegative(value, name);
        if (exact.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return exact;
    }

    static BigDecimal nonNegative(BigDecimal value, String name) {
        BigDecimal exact = exact(value, name);
        if (exact.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return exact;
    }

    /** Fixes the canonical scale, refusing a value that does not survive it exactly. */
    static BigDecimal exact(BigDecimal value, String name) {
        try {
            return required(value, name).setScale(SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException tooPrecise) {
            throw new IllegalArgumentException(
                    name + " carries more precision than canonical stores", tooPrecise);
        }
    }

    static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(SCALE);
    }

    static String currencyCode(String value, String name) {
        String code = nonBlank(value, name).toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException(name + " must be an ISO-like three-letter code");
        }
        return code;
    }

    static String nonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    static <T> T required(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }
}
