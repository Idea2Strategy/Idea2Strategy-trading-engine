package com.idea2strategy.trading.strategy.runtime.candidate;

import com.idea2strategy.trading.strategy.runtime.basic.BasicOrderSide;
import com.idea2strategy.trading.strategy.runtime.basic.EqualAllocationShare;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record BasicOrderCandidate(
        UUID candidateId,
        String flowId,
        UUID instrumentId,
        BasicOrderSide side,
        Optional<EqualAllocationShare> buyAllocation,
        Map<String, String> actionParameters) {

    public BasicOrderCandidate {
        candidateId = Objects.requireNonNull(candidateId, "candidateId must not be null");
        flowId = CandidateValueValidation.requireText(flowId, "flowId");
        instrumentId = Objects.requireNonNull(instrumentId, "instrumentId must not be null");
        side = Objects.requireNonNull(side, "side must not be null");
        buyAllocation = Objects.requireNonNull(buyAllocation, "buyAllocation must not be null");
        actionParameters = Map.copyOf(Objects.requireNonNull(actionParameters, "actionParameters must not be null"));
        if ((side == BasicOrderSide.BUY) != buyAllocation.isPresent()) {
            throw new IllegalArgumentException("only a buy candidate requires an equal allocation share");
        }
    }
}
