package com.idea2strategy.trading.domain.reservation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * The quote and the arithmetic a cash reservation was sized from.
 *
 * <p>Canonical keeps every term separately — the reference price and the market state it came from,
 * the base notional, the fixed slippage, the estimated fee and the buying-power buffer — so a
 * reservation stays explainable after the policies that produced it have moved on. The private
 * schema kept only the final number.
 *
 * <p>These columns are nullable in canonical and carry no CHECK of their own beyond
 * {@code cash_policies_only_for_buying_power}, which forbids {@code buffer_amount} on anything but
 * buying power. Requiring the terms to add up to the reserved amount is this record's own rule: the
 * buffer is what {@code SETTLED_BY_FILL} hands back, and a buffer that does not reconcile with the
 * reserved total would make that release unexplainable.
 */
public record ReservationPricing(
        BigDecimal referencePrice,
        Instant referenceObservedAt,
        String referenceMarketHash,
        BigDecimal baseNotional,
        BigDecimal fixedSlippageAmount,
        BigDecimal estimatedFeeAmount,
        BigDecimal bufferAmount) {

    public ReservationPricing {
        referencePrice = optionalPositive(referencePrice, "referencePrice");
        baseNotional = optionalPositive(baseNotional, "baseNotional");
        // Slippage, fee and buffer are all rate-driven and legitimately zero.
        fixedSlippageAmount = optionalNonNegative(fixedSlippageAmount, "fixedSlippageAmount");
        estimatedFeeAmount = optionalNonNegative(estimatedFeeAmount, "estimatedFeeAmount");
        bufferAmount = optionalNonNegative(bufferAmount, "bufferAmount");
        if ((referenceObservedAt == null) != (referenceMarketHash == null)) {
            throw new IllegalArgumentException(
                    "a reference observation needs both its moment and its market hash");
        }
    }

    /** A reservation canonical stores no money terms for, which is every quantity reservation. */
    public static ReservationPricing none() {
        return new ReservationPricing(null, null, null, null, null, null, null);
    }

    /** Buying power: base notional plus fixed slippage plus estimated fee plus buffer. */
    public static ReservationPricing buyingPower(
            BigDecimal referencePrice,
            Instant referenceObservedAt,
            String referenceMarketHash,
            BigDecimal baseNotional,
            BigDecimal fixedSlippageAmount,
            BigDecimal estimatedFeeAmount,
            BigDecimal bufferAmount) {
        return new ReservationPricing(
                Objects.requireNonNull(referencePrice, "referencePrice"),
                Objects.requireNonNull(referenceObservedAt, "referenceObservedAt"),
                ReservationValues.nonBlank(referenceMarketHash, "referenceMarketHash"),
                Objects.requireNonNull(baseNotional, "baseNotional"),
                Objects.requireNonNull(fixedSlippageAmount, "fixedSlippageAmount"),
                Objects.requireNonNull(estimatedFeeAmount, "estimatedFeeAmount"),
                Objects.requireNonNull(bufferAmount, "bufferAmount"));
    }

    /** Short collateral: canonical forbids a buffer here, so the terms stop at the estimated fee. */
    public static ReservationPricing shortCollateral(
            BigDecimal referencePrice,
            Instant referenceObservedAt,
            String referenceMarketHash,
            BigDecimal baseNotional,
            BigDecimal fixedSlippageAmount,
            BigDecimal estimatedFeeAmount) {
        return new ReservationPricing(
                Objects.requireNonNull(referencePrice, "referencePrice"),
                Objects.requireNonNull(referenceObservedAt, "referenceObservedAt"),
                ReservationValues.nonBlank(referenceMarketHash, "referenceMarketHash"),
                Objects.requireNonNull(baseNotional, "baseNotional"),
                Objects.requireNonNull(fixedSlippageAmount, "fixedSlippageAmount"),
                Objects.requireNonNull(estimatedFeeAmount, "estimatedFeeAmount"), null);
    }

    void requireFits(ReservationResourceType resourceType, BigDecimal reserved) {
        if (bufferAmount != null && resourceType != ReservationResourceType.CASH_BUYING_POWER) {
            throw new IllegalArgumentException("only a buying power reservation carries a buffer");
        }
        if (resourceType == ReservationResourceType.POSITION_QUANTITY
                && (referencePrice != null || baseNotional != null || fixedSlippageAmount != null
                        || estimatedFeeAmount != null)) {
            throw new IllegalArgumentException("a quantity reservation is not sized in money");
        }
        if (baseNotional == null || fixedSlippageAmount == null || estimatedFeeAmount == null) {
            return;
        }
        BigDecimal total = baseNotional.add(fixedSlippageAmount).add(estimatedFeeAmount)
                .add(bufferAmount == null ? ReservationValues.zero() : bufferAmount);
        if (total.compareTo(reserved) != 0) {
            throw new IllegalArgumentException(
                    "the reservation terms add up to " + total + " but " + reserved + " is reserved");
        }
    }

    private static BigDecimal optionalPositive(BigDecimal value, String name) {
        return value == null ? null : ReservationValues.positive(value, name);
    }

    private static BigDecimal optionalNonNegative(BigDecimal value, String name) {
        return value == null ? null : ReservationValues.nonNegative(value, name);
    }
}
