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
 */
public record CandidateOrder(
        UUID candidateId,
        UUID instrumentId,
        UUID flowId,
        String side,
        BigDecimal quantity,
        BigDecimal limitPrice,
        List<String> reasonCodes) {

    public CandidateOrder {
        candidateId = Objects.requireNonNull(candidateId, "candidateId");
        instrumentId = Objects.requireNonNull(instrumentId, "instrumentId");
        side = Objects.requireNonNull(side, "side");
        quantity = Objects.requireNonNull(quantity, "quantity");
        reasonCodes = List.copyOf(Objects.requireNonNull(reasonCodes, "reasonCodes"));
    }

    /** The unscoped shape, kept for candidates that arrive on schema version 1. */
    public CandidateOrder(
            UUID candidateId, UUID instrumentId, String side, BigDecimal quantity,
            BigDecimal limitPrice, List<String> reasonCodes) {
        this(candidateId, instrumentId, null, side, quantity, limitPrice, reasonCodes);
    }

    public Optional<UUID> flow() {
        return Optional.ofNullable(flowId);
    }
}
