package com.idea2strategy.trading.domain.candidate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One candidate order within a batch.
 *
 * <p>{@code flowId} is optional only because the upstream contract carried none before schema
 * version 2. The canonical {@code trading.order_intents} row requires {@code flow_id} NOT NULL under
 * a composite foreign key to {@code bot.flows}.
 *
 * <p>{@code quantity} is optional from schema version 3, where a buy carries an
 * {@link CandidateAllocation} share instead and a sell carries neither. Sizing is this service's
 * work: only it holds the partition's spendable cash, the reference price, the fee and the buying
 * power buffer, and only it knows what a sell's position actually holds. A candidate that arrives
 * with neither measure is therefore not incomplete — it is a decision awaiting the sizing that F02
 * assigns here.
 */
public record CandidateOrder(
        UUID candidateId,
        UUID instrumentId,
        UUID flowId,
        String side,
        BigDecimal quantity,
        CandidateAllocation allocation,
        BigDecimal limitPrice,
        List<String> reasonCodes) {

    public CandidateOrder {
        candidateId = Objects.requireNonNull(candidateId, "candidateId");
        instrumentId = Objects.requireNonNull(instrumentId, "instrumentId");
        side = Objects.requireNonNull(side, "side");
        if (quantity != null && quantity.signum() <= 0) {
            throw new IllegalArgumentException("quantity must be positive when present");
        }
        if (allocation != null) {
            if (quantity != null) {
                throw new IllegalArgumentException(
                        "a candidate carries either a quantity or an allocation share, never both");
            }
            if (!"BUY".equals(side)) {
                throw new IllegalArgumentException(
                        "only a BUY candidate carries an allocation share; a sell is sized from the "
                                + "position held");
            }
        }
        reasonCodes = List.copyOf(Objects.requireNonNull(reasonCodes, "reasonCodes"));
    }

    /** The unscoped shape, kept for candidates that arrive on schema version 1. */
    public CandidateOrder(
            UUID candidateId, UUID instrumentId, String side, BigDecimal quantity,
            BigDecimal limitPrice, List<String> reasonCodes) {
        this(candidateId, instrumentId, null, side, quantity, null, limitPrice, reasonCodes);
    }

    /** The version 2 shape: partition-scoped, still carrying its own quantity. */
    public CandidateOrder(
            UUID candidateId, UUID instrumentId, UUID flowId, String side, BigDecimal quantity,
            BigDecimal limitPrice, List<String> reasonCodes) {
        this(candidateId, instrumentId, flowId, side, quantity, null, limitPrice, reasonCodes);
    }

    public Optional<UUID> flow() {
        return Optional.ofNullable(flowId);
    }

    /** The quantity a version 1 or 2 producer decided, absent from version 3. */
    public Optional<BigDecimal> requestedQuantity() {
        return Optional.ofNullable(quantity);
    }

    /** The share of spendable cash a version 3 buy claims. */
    public Optional<CandidateAllocation> allocationShare() {
        return Optional.ofNullable(allocation);
    }
}
