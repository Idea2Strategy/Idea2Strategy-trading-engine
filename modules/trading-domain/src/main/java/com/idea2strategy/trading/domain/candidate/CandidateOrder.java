package com.idea2strategy.trading.domain.candidate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record CandidateOrder(
        UUID candidateId,
        UUID instrumentId,
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
}
