package com.idea2strategy.trading.messaging.evaluation;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One order candidate produced by an evaluation.
 *
 * <p>{@code flowId} arrives with schema version 2. The canonical {@code trading.order_intents} row
 * this candidate becomes carries {@code flow_id} NOT NULL under a composite foreign key
 * {@code (partition_id, flow_id) -> bot.flows(partition_id, id)}, so the flow that produced the
 * candidate has to travel with it. A version 1 candidate still deserialises but cannot become a
 * canonical intent.
 *
 * <p><strong>Schema version 3 carries an allocation share instead of a quantity.</strong> A candidate
 * is a decision about <em>what</em> to trade, not <em>how much</em>: turning a share into shares needs
 * the partition's spendable cash, the reference price, the fee and the buying power buffer, and all
 * four belong to the consumer by F02. The producer's evaluation only ever knew the share — C's
 * executor yields an exact {@code 1/N} equal allocation — so a contract demanding a quantity forced
 * it to invent one it could not compute. D's {@code emit_order_candidate} already models it this way,
 * which is what keeps the backtest and live runtimes agreeing (D92).
 *
 * <p>A sell carries neither measure: its size is the position actually held, which only the
 * consumer's lot projection knows. A share on a sell would imply a fraction of something the producer
 * cannot see.
 *
 * <p>Versions 1 and 2 keep deserialising with their quantity, so an existing producer is unaffected.
 * The per-version shape rule lives in the batch adapter, where the schema version is known; what this
 * record enforces are the invariants that hold at every version.
 */
public record OrderCandidate(
        UUID candidateId,
        UUID instrumentId,
        UUID flowId,
        OrderSide side,
        BigDecimal quantity,
        Integer allocationNumerator,
        Integer allocationDenominator,
        BigDecimal referencePrice,
        BigDecimal limitPrice,
        List<String> reasonCodes,
        Integer positionPercent) {

    public OrderCandidate {
        candidateId = Objects.requireNonNull(candidateId, "candidateId");
        instrumentId = Objects.requireNonNull(instrumentId, "instrumentId");
        side = Objects.requireNonNull(side, "side");
        if (quantity != null) {
            quantity = requirePositive(quantity, "quantity");
        }
        if (limitPrice != null) {
            limitPrice = requirePositive(limitPrice, "limitPrice");
        }
        if (referencePrice != null) {
            referencePrice = requirePositive(referencePrice, "referencePrice");
        }
        requireAllocation(side, quantity, allocationNumerator, allocationDenominator);
        if (positionPercent != null
                && (side != OrderSide.SELL || positionPercent < 1 || positionPercent > 100)) {
            throw new IllegalArgumentException(
                    "positionPercent is allowed only for SELL and must be between 1 and 100");
        }
        reasonCodes = List.copyOf(Objects.requireNonNull(reasonCodes, "reasonCodes"));
    }

    public OrderCandidate(
            UUID candidateId,
            UUID instrumentId,
            UUID flowId,
            OrderSide side,
            BigDecimal quantity,
            Integer allocationNumerator,
            Integer allocationDenominator,
            BigDecimal referencePrice,
            BigDecimal limitPrice,
            List<String> reasonCodes) {
        this(candidateId, instrumentId, flowId, side, quantity, allocationNumerator,
                allocationDenominator, referencePrice, limitPrice, reasonCodes, null);
    }

    /** The version 1 shape, kept so an existing producer keeps deserialising unchanged. */
    public OrderCandidate(
            UUID candidateId,
            UUID instrumentId,
            OrderSide side,
            BigDecimal quantity,
            BigDecimal limitPrice,
            List<String> reasonCodes) {
        this(candidateId, instrumentId, null, side, quantity, null, null, null, limitPrice, reasonCodes);
    }

    /** The version 2 shape: the partition scope had arrived, the quantity had not yet left. */
    public OrderCandidate(
            UUID candidateId,
            UUID instrumentId,
            UUID flowId,
            OrderSide side,
            BigDecimal quantity,
            BigDecimal limitPrice,
            List<String> reasonCodes) {
        this(candidateId, instrumentId, flowId, side, quantity, null, null, null, limitPrice, reasonCodes);
    }

    /**
     * A version 3 buy: the share of the partition's spendable cash this candidate claims, and the
     * price the evaluation decided on.
     *
     * <p>The reference price travels because it is the only mark that exists for an instrument no fill
     * has ever touched, and it is what D's candidate carries for the same reason. It is not a limit:
     * the order is still MARKET unless a limit is given separately.
     */
    public static OrderCandidate allocatedBuy(
            UUID candidateId,
            UUID instrumentId,
            UUID flowId,
            int allocationNumerator,
            int allocationDenominator,
            BigDecimal referencePrice,
            BigDecimal limitPrice,
            List<String> reasonCodes) {
        return new OrderCandidate(
                candidateId, instrumentId, flowId, OrderSide.BUY, null,
                allocationNumerator, allocationDenominator, referencePrice, limitPrice, reasonCodes);
    }

    /** A version 3 sell: sized from the position held, so it carries no measure of its own. */
    public static OrderCandidate heldSell(
            UUID candidateId,
            UUID instrumentId,
            UUID flowId,
            BigDecimal referencePrice,
            BigDecimal limitPrice,
            List<String> reasonCodes) {
        return new OrderCandidate(
                candidateId, instrumentId, flowId, OrderSide.SELL, null, null, null,
                referencePrice, limitPrice, reasonCodes);
    }

    public static OrderCandidate partialHeldSell(
            UUID candidateId,
            UUID instrumentId,
            UUID flowId,
            int positionPercent,
            BigDecimal referencePrice,
            BigDecimal limitPrice,
            List<String> reasonCodes) {
        return new OrderCandidate(
                candidateId, instrumentId, flowId, OrderSide.SELL, null, null, null,
                referencePrice, limitPrice, reasonCodes, positionPercent);
    }

    public Optional<Integer> requestedPositionPercent() {
        return Optional.ofNullable(positionPercent);
    }

    /** The owning flow, present from schema version 2. */
    public Optional<UUID> flow() {
        return Optional.ofNullable(flowId);
    }

    /** The quantity a version 1 or 2 producer decided, absent from version 3. */
    public Optional<BigDecimal> requestedQuantity() {
        return Optional.ofNullable(quantity);
    }

    /** True when this candidate is sized by the consumer from an allocation share. */
    public boolean carriesAllocation() {
        return allocationNumerator != null;
    }

    /**
     * The share of spendable cash this candidate claims, as its two exact integers.
     *
     * <p>Deliberately not a {@code BigDecimal}: a share like {@code 1/3} stays exact until the
     * consumer multiplies it by a budget, and pre-dividing would bake a rounding decision into the
     * contract when the consumer's own precision rules own that decision.
     */
    public Optional<AllocationShare> allocation() {
        return carriesAllocation()
                ? Optional.of(new AllocationShare(allocationNumerator, allocationDenominator))
                : Optional.empty();
    }

    /** An exact fraction of a partition's spendable cash, in {@code (0, 1]}. */
    public record AllocationShare(int numerator, int denominator) {
        public AllocationShare {
            if (numerator < 1 || denominator < 1 || numerator > denominator) {
                throw new IllegalArgumentException(
                        "an allocation share must be an exact fraction in (0, 1], got "
                                + numerator + "/" + denominator);
            }
        }
    }

    /**
     * Invariants that hold at every version: a share is an exact fraction in {@code (0, 1]}, a
     * candidate never carries both measures, and a sell never carries a buy share.
     */
    private static void requireAllocation(
            OrderSide side, BigDecimal quantity, Integer numerator, Integer denominator) {
        if (numerator == null && denominator == null) {
            return;
        }
        if (numerator == null || denominator == null) {
            throw new IllegalArgumentException(
                    "an allocation share needs both a numerator and a denominator");
        }
        if (side == OrderSide.SELL) {
            throw new IllegalArgumentException(
                    "a SELL candidate must not carry a buy allocation share: its size is the "
                            + "position held, which only the consumer's lots know");
        }
        if (quantity != null) {
            throw new IllegalArgumentException(
                    "a candidate carries either a quantity or an allocation share, never both");
        }
        new AllocationShare(numerator, denominator);
    }

    private static BigDecimal requirePositive(BigDecimal value, String field) {
        Objects.requireNonNull(value, field);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }
}
