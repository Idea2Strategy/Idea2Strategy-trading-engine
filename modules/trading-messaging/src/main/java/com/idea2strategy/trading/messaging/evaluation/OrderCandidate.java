package com.idea2strategy.trading.messaging.evaluation;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record OrderCandidate(
        UUID candidateId,
        UUID instrumentId,
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

    private static BigDecimal requirePositive(BigDecimal value, String field) {
        Objects.requireNonNull(value, field);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }
}
