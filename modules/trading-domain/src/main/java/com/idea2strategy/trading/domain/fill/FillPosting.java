package com.idea2strategy.trading.domain.fill;

import com.idea2strategy.trading.domain.order.OrderScope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A fill as the canonical model records it.
 *
 * <p>{@link FillRecord} stays the revision-aware domain record and is not widened. What canonical
 * needs on top of it is evidence and attribution: the quote the fill was priced from, the platform
 * rules it was charged under, the official event that caused it, and how it splits across the order
 * components. Keeping those here leaves the proven fill and correction rules untouched.
 *
 * <p>The economics are carried rather than recomputed. The realistic fill model already derived the
 * reference price, slippage and fee, and canonical stores each of them separately so a past fill
 * stays explainable even after the policies move on.
 */
public record FillPosting(
        FillRecord record,
        OrderScope scope,
        UUID botEventId,
        UUID feePolicyId,
        int feeRateBps,
        String precisionRulesVersion,
        BigDecimal referencePrice,
        Instant referenceObservedAt,
        String referenceMarketHash,
        BigDecimal grossAmount,
        BigDecimal feeBasisAmount,
        BigDecimal settlementCashDelta,
        String allocationRulesVersion,
        List<FillAllocation> allocations) {

    /** Canonical {@code fill_fixed_slippage_five_bps}: the product fixes slippage at 0.05%. */
    public static final int FIXED_SLIPPAGE_RATE_BPS = 5;

    private static final int AMOUNT_SCALE = 8;

    public FillPosting {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(botEventId, "botEventId");
        Objects.requireNonNull(feePolicyId, "feePolicyId");
        Objects.requireNonNull(precisionRulesVersion, "precisionRulesVersion");
        Objects.requireNonNull(referenceObservedAt, "referenceObservedAt");
        Objects.requireNonNull(referenceMarketHash, "referenceMarketHash");
        Objects.requireNonNull(allocationRulesVersion, "allocationRulesVersion");

        referencePrice = exact(referencePrice, "referencePrice");
        grossAmount = exact(grossAmount, "grossAmount");
        feeBasisAmount = exact(feeBasisAmount, "feeBasisAmount");
        settlementCashDelta = exact(settlementCashDelta, "settlementCashDelta");

        if (referenceObservedAt.isAfter(record.occurredAt())) {
            throw new IllegalArgumentException("a fill cannot be priced from a later quote");
        }
        allocations = List.copyOf(Objects.requireNonNull(allocations, "allocations"));

        // Only an original carries economics and attribution of its own. A later revision is stored
        // as a delta against the original, so its amounts are derived there rather than asserted
        // here, and a bust legitimately reports nothing.
        if (record.revision() == 0) {
            if (referencePrice.signum() <= 0) {
                throw new IllegalArgumentException("referencePrice must be positive");
            }
            if (grossAmount.signum() <= 0) {
                throw new IllegalArgumentException("grossAmount must be positive");
            }
            if (feeBasisAmount.signum() <= 0) {
                throw new IllegalArgumentException("feeBasisAmount must be positive");
            }
            requireAllocationsMatchTheFill(
                    allocations, record.quantity(), grossAmount, record.commission(), settlementCashDelta);
        } else {
            if (!allocations.isEmpty()) {
                throw new IllegalArgumentException(
                        "a revision adjusts the original fill and takes no allocations of its own");
            }
            // Canonical fill_correction_does_not_change_quantity: only a reversal may move quantity.
            // A re-reported quantity has to bust the original and report a new fill.
            if (record.kind() == FillRecordKind.CORRECTION) {
                throw new IllegalArgumentException(
                        "a canonical correction cannot change quantity; bust the fill and report a new one");
            }
        }
    }

    /**
     * Mirrors canonical {@code assert_fill_allocation_totals}, which fails the transaction at commit.
     * Checking here turns a deferred database error into a readable one at the boundary that caused
     * it.
     */
    private static void requireAllocationsMatchTheFill(
            List<FillAllocation> allocations,
            BigDecimal quantity,
            BigDecimal grossAmount,
            BigDecimal feeAmount,
            BigDecimal settlementCashDelta) {
        if (allocations.isEmpty()) {
            throw new IllegalArgumentException("a fill must be allocated to at least one component");
        }
        Set<UUID> components = new HashSet<>();
        Set<Integer> sequences = new HashSet<>();
        BigDecimal quantityTotal = BigDecimal.ZERO;
        BigDecimal grossTotal = BigDecimal.ZERO;
        BigDecimal feeTotal = BigDecimal.ZERO;
        BigDecimal cashTotal = BigDecimal.ZERO;
        for (FillAllocation allocation : allocations) {
            if (!components.add(allocation.orderComponentId())) {
                throw new IllegalArgumentException("a component may take one allocation per fill");
            }
            if (!sequences.add(allocation.allocationSequence())) {
                throw new IllegalArgumentException("allocation sequences must be distinct");
            }
            quantityTotal = quantityTotal.add(allocation.allocatedQuantity());
            grossTotal = grossTotal.add(allocation.allocatedGrossAmount());
            feeTotal = feeTotal.add(allocation.allocatedFeeAmount());
            cashTotal = cashTotal.add(allocation.allocatedSettlementCashDelta());
        }
        requireSame(quantityTotal, quantity, "quantity");
        requireSame(grossTotal, grossAmount, "gross amount");
        requireSame(feeTotal, feeAmount, "fee");
        requireSame(cashTotal, settlementCashDelta, "settlement cash delta");
    }

    private static void requireSame(BigDecimal total, BigDecimal expected, String name) {
        if (total.compareTo(expected) != 0) {
            throw new IllegalArgumentException(
                    "allocated " + name + " " + total.toPlainString()
                            + " does not match the fill's " + expected.toPlainString());
        }
    }

    private static BigDecimal exact(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        try {
            return value.setScale(AMOUNT_SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException notExactlyRepresentable) {
            throw new IllegalArgumentException(
                    name + " exceeds the canonical scale of " + AMOUNT_SCALE, notExactlyRepresentable);
        }
    }
}
