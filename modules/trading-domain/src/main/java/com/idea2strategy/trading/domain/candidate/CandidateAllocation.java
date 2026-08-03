package com.idea2strategy.trading.domain.candidate;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * The exact fraction of a partition's spendable cash one buy candidate claims.
 *
 * <p>Held as two integers rather than a decimal so a share like {@code 1/3} is still exact when it
 * meets a budget. Pre-dividing would round before the amount it applies to is known, and the
 * rounding rules belong with the money.
 *
 * <p>C's Basic executor produces equal allocation, an exact {@code 1/N} over the instruments a flow
 * evaluated. The range is widened to any fraction in {@code (0, 1]} because the consumer's sizing
 * does not care how the producer arrived at the share, and D's own emission already permits any
 * exact fraction in that range.
 */
public record CandidateAllocation(int numerator, int denominator) {

    public CandidateAllocation {
        if (numerator < 1 || denominator < 1 || numerator > denominator) {
            throw new IllegalArgumentException(
                    "an allocation share must be an exact fraction in (0, 1], got "
                            + numerator + "/" + denominator);
        }
    }

    /** Equal allocation over {@code parts} instruments, which is what a Basic flow produces. */
    public static CandidateAllocation equalOf(int parts) {
        return new CandidateAllocation(1, parts);
    }

    /**
     * This share of {@code budget}, at the caller's precision.
     *
     * <p>Multiplying before dividing keeps the exactness the two integers were kept for: with a
     * budget of 100 and a share of 1/3, {@code 100 × 1 ÷ 3} carries the full working precision,
     * where {@code (1 ÷ 3) × 100} would have rounded first.
     */
    public BigDecimal of(BigDecimal budget, MathContext context) {
        return budget.multiply(BigDecimal.valueOf(numerator), context)
                .divide(BigDecimal.valueOf(denominator), context);
    }
}
