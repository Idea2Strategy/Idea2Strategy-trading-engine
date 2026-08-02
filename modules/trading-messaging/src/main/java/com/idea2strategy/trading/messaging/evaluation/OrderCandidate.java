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
 */
public record OrderCandidate(
        UUID candidateId,
        UUID instrumentId,
        UUID flowId,
        OrderSide side,
        BigDecimal quantity,
        BigDecimal limitPrice,
        List<String> reasonCodes) {

    public OrderCandidate {
        candidateId = Objects.requireNonNull(candidateId, "candidateId");
        instrumentId = Objects.requireNonNull(instrumentId, "instrumentId");
        side = Objects.requireNonNull(side, "side");
        quantity = requirePositive(quantity, "quantity");
        if (limitPrice != null) {
            limitPrice = requirePositive(limitPrice, "limitPrice");
        }
        reasonCodes = List.copyOf(Objects.requireNonNull(reasonCodes, "reasonCodes"));
    }

    /** The version 1 shape, kept so an existing producer keeps deserialising unchanged. */
    public OrderCandidate(
            UUID candidateId,
            UUID instrumentId,
            OrderSide side,
            BigDecimal quantity,
            BigDecimal limitPrice,
            List<String> reasonCodes) {
        this(candidateId, instrumentId, null, side, quantity, limitPrice, reasonCodes);
    }

    /** The owning flow, present from schema version 2. */
    public Optional<UUID> flow() {
        return Optional.ofNullable(flowId);
    }

    private static BigDecimal requirePositive(BigDecimal value, String field) {
        Objects.requireNonNull(value, field);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }
}
