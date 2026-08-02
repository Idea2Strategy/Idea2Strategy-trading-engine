package com.idea2strategy.trading.domain.budget;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * The canonical shape rules the budget projection rows are held to.
 *
 * <p>Nothing here decides what a budget <em>is</em>. It only refuses values the canonical columns
 * cannot hold faithfully, at the boundary that supplied them rather than at commit.
 */
final class BudgetProjectionValues {

    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final Pattern VALUATION_STATUS = Pattern.compile("[A-Z][A-Z0-9_]*");

    private BudgetProjectionValues() {}

    static <T> T required(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }

    /**
     * Fixes an amount at the canonical scale and refuses to round it there. PostgreSQL always
     * returns {@code numeric(24,8)} at scale 8, so an amount that only fits after rounding would
     * hash differently from the row it produced.
     */
    static BigDecimal exact(BigDecimal value, String name) {
        required(value, name);
        try {
            return value.setScale(BudgetProjection.SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException notExactlyRepresentable) {
            throw new IllegalArgumentException(
                    name + " exceeds the canonical scale of " + BudgetProjection.SCALE,
                    notExactlyRepresentable);
        }
    }

    /** Canonical {@code *_nonnegative} CHECKs, refused before the row is built. */
    static BigDecimal nonNegative(BigDecimal value, String name) {
        BigDecimal normalized = exact(value, name);
        if (normalized.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return normalized;
    }

    /** Canonical {@code partition_budget_cap_positive}. */
    static BigDecimal positive(BigDecimal value, String name) {
        BigDecimal normalized = nonNegative(value, name);
        if (normalized.signum() == 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return normalized;
    }

    /** Canonical {@code char(3)}, which pads rather than refuses anything shorter. */
    static String currencyCode(String value) {
        required(value, "currencyCode");
        if (!CURRENCY.matcher(value).matches()) {
            throw new IllegalArgumentException("currencyCode must be three upper-case letters");
        }
        return value;
    }

    /**
     * Canonical constrains {@code valuation_status} only by length, so the vocabulary belongs to
     * whoever values the position and is carried through untouched. Only the shape is enforced:
     * something storable, upper case, and short enough for the column.
     */
    static String valuationStatus(String value) {
        required(value, "valuationStatus");
        if (value.length() > BudgetProjection.VALUATION_STATUS_MAX_LENGTH
                || !VALUATION_STATUS.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "valuationStatus must be an upper-case code of at most "
                            + BudgetProjection.VALUATION_STATUS_MAX_LENGTH + " characters");
        }
        return value;
    }

    /**
     * {@code timestamptz} keeps microseconds. Truncating here means the digest is taken over the
     * instant canonical will actually store, so a projection compared with its own reload agrees.
     */
    static Instant valuationAt(Instant value) {
        return required(value, "valuationAt").truncatedTo(ChronoUnit.MICROS);
    }

    static long eventSequence(long value) {
        if (value < 1) {
            throw new IllegalArgumentException("lastEventSequence must be positive");
        }
        return value;
    }

    /** Length-prefixed SHA-256 over the stored values, so no two field lists can collide. */
    static String hash(String... fields) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
        for (String field : fields) {
            byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
