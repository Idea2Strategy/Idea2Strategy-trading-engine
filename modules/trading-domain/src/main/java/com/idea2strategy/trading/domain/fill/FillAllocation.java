package com.idea2strategy.trading.domain.fill;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

/**
 * How much of one fill belongs to one order component.
 *
 * <p>An order can be composed of several intents, so a fill against it has to be attributed back
 * before it can reach a position or the ledger. Canonical
 * {@code assert_fill_allocation_totals} requires the allocations of a fill to sum exactly to its
 * quantity, gross amount, fee and cash delta, which is why nothing here is allowed to be rounded.
 *
 * <p>{@code settlementCashDelta} is signed: cash leaves on a buy and arrives on a sell. Canonical
 * forbids zero, because an allocation that moves no cash is not an allocation.
 */
public record FillAllocation(
        UUID orderComponentId,
        int allocationSequence,
        BigDecimal allocatedQuantity,
        BigDecimal allocatedGrossAmount,
        BigDecimal allocatedFeeAmount,
        BigDecimal allocatedSettlementCashDelta) {

    private static final int QUANTITY_SCALE = 8;
    private static final int AMOUNT_SCALE = 8;

    public FillAllocation {
        Objects.requireNonNull(orderComponentId, "orderComponentId");
        if (allocationSequence <= 0) {
            throw new IllegalArgumentException("allocationSequence must be positive");
        }
        allocatedQuantity = exact(allocatedQuantity, QUANTITY_SCALE, "allocatedQuantity");
        allocatedGrossAmount = exact(allocatedGrossAmount, AMOUNT_SCALE, "allocatedGrossAmount");
        allocatedFeeAmount = exact(allocatedFeeAmount, AMOUNT_SCALE, "allocatedFeeAmount");
        allocatedSettlementCashDelta =
                exact(allocatedSettlementCashDelta, AMOUNT_SCALE, "allocatedSettlementCashDelta");

        if (allocatedQuantity.signum() <= 0) {
            throw new IllegalArgumentException("allocatedQuantity must be positive");
        }
        if (allocatedGrossAmount.signum() <= 0) {
            throw new IllegalArgumentException("allocatedGrossAmount must be positive");
        }
        if (allocatedFeeAmount.signum() < 0) {
            throw new IllegalArgumentException("allocatedFeeAmount must not be negative");
        }
        if (allocatedSettlementCashDelta.signum() == 0) {
            throw new IllegalArgumentException("allocatedSettlementCashDelta must not be zero");
        }
    }

    private static BigDecimal exact(BigDecimal value, int scale, String name) {
        Objects.requireNonNull(value, name);
        try {
            return value.setScale(scale, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException notExactlyRepresentable) {
            throw new IllegalArgumentException(
                    name + " exceeds the canonical scale of " + scale, notExactlyRepresentable);
        }
    }
}
