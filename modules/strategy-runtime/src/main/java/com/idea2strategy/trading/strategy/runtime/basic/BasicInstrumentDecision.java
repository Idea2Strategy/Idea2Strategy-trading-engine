package com.idea2strategy.trading.strategy.runtime.basic;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record BasicInstrumentDecision(
        String flowId,
        UUID instrumentId,
        BasicOrderSide side,
        BasicDecisionStatus status,
        List<BasicStepTrace> trace,
        Optional<String> firstFailureStepId,
        Optional<String> firstFailureReason,
        Optional<EqualAllocationShare> buyAllocation) {

    public BasicInstrumentDecision {
        flowId = BasicValueValidation.requireText(flowId, "flowId");
        instrumentId = Objects.requireNonNull(instrumentId, "instrumentId must not be null");
        side = Objects.requireNonNull(side, "side must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
        trace = List.copyOf(Objects.requireNonNull(trace, "trace must not be null"));
        firstFailureStepId = Objects.requireNonNull(firstFailureStepId, "firstFailureStepId must not be null");
        firstFailureReason = Objects.requireNonNull(firstFailureReason, "firstFailureReason must not be null");
        buyAllocation = Objects.requireNonNull(buyAllocation, "buyAllocation must not be null");
        if (status == BasicDecisionStatus.CANDIDATE) {
            if (firstFailureStepId.isPresent() || firstFailureReason.isPresent()) {
                throw new IllegalArgumentException("a candidate cannot have a failure");
            }
        } else if (firstFailureStepId.isEmpty() || firstFailureReason.isEmpty() || buyAllocation.isPresent()) {
            throw new IllegalArgumentException("a rejected decision requires one failure and no allocation");
        }
        if (buyAllocation.isPresent() && side != BasicOrderSide.BUY) {
            throw new IllegalArgumentException("only buy candidates have an equal allocation share");
        }
    }

    BasicInstrumentDecision withBuyAllocation(EqualAllocationShare allocation) {
        return new BasicInstrumentDecision(
                flowId, instrumentId, side, status, trace,
                firstFailureStepId, firstFailureReason, Optional.of(allocation));
    }
}
