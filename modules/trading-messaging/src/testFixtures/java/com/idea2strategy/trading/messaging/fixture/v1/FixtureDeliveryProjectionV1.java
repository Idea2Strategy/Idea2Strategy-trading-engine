package com.idea2strategy.trading.messaging.fixture.v1;

import com.idea2strategy.trading.messaging.contract.v1.ContractValidationV1;
import com.idea2strategy.trading.messaging.contract.v1.OrderLifecycleContractV1;
import com.idea2strategy.trading.messaging.contract.v1.TradingEnvelopeV1;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class FixtureDeliveryProjectionV1 {
    public enum DeliveryResult { APPLIED, DUPLICATE, STALE }

    private final Set<UUID> appliedEventIds = new HashSet<>();
    private final Map<UUID, Long> latestVersionByAggregate = new HashMap<>();
    private final Map<UUID, OrderLifecycleContractV1.EventType> orderStateByAggregate = new HashMap<>();
    private final Map<UUID, BigDecimal> orderQuantityByAggregate = new HashMap<>();
    private final Map<UUID, BigDecimal> filledQuantityByAggregate = new HashMap<>();
    private int tradeCount;
    private int ledgerEntryCount;

    public DeliveryResult accept(TradingEnvelopeV1<?> envelope) {
        ContractValidationV1.required(envelope, "envelope");
        if (appliedEventIds.contains(envelope.eventId())) {
            return DeliveryResult.DUPLICATE;
        }

        long latestVersion = latestVersionByAggregate.getOrDefault(envelope.aggregateId(), 0L);
        if (envelope.aggregateVersion() <= latestVersion) {
            return DeliveryResult.STALE;
        }
        if (envelope.aggregateVersion() != latestVersion + 1) {
            throw new IllegalStateException("aggregate version gap: sequence gap");
        }

        if (envelope.payload() instanceof OrderLifecycleContractV1.Event event) {
            validateLifecycle(envelope.aggregateId(), event);
        }

        appliedEventIds.add(envelope.eventId());
        latestVersionByAggregate.put(envelope.aggregateId(), envelope.aggregateVersion());
        if (envelope.payload() instanceof OrderLifecycleContractV1.Event event) {
            applyLifecycle(envelope.aggregateId(), event);
            if (event.ledgerTransaction() != null) {
                tradeCount++;
                ledgerEntryCount += event.ledgerTransaction().entries().size();
            }
        }
        return DeliveryResult.APPLIED;
    }

    private void validateLifecycle(UUID aggregateId, OrderLifecycleContractV1.Event event) {
        var current = orderStateByAggregate.get(aggregateId);
        if (current == null) {
            if (event.type() != OrderLifecycleContractV1.EventType.ACCEPTED
                && event.type() != OrderLifecycleContractV1.EventType.REJECTED) {
                throw new IllegalStateException("order lifecycle must start with ACCEPTED or REJECTED");
            }
        } else {
            if (isTerminal(current)) {
                throw new IllegalStateException("order lifecycle cannot transition after terminal state " + current);
            }
            if (event.type() == OrderLifecycleContractV1.EventType.ACCEPTED
                || event.type() == OrderLifecycleContractV1.EventType.REJECTED) {
                throw new IllegalStateException("invalid order lifecycle transition from " + current + " to " + event.type());
            }
            if (event.orderQuantity().asBigDecimal().compareTo(orderQuantityByAggregate.get(aggregateId)) != 0) {
                throw new IllegalStateException("orderQuantity changed within an order lifecycle");
            }
        }

        if (event.fillQuantity() != null) {
            var cumulative = filledQuantityByAggregate.getOrDefault(aggregateId, BigDecimal.ZERO)
                .add(event.fillQuantity().asBigDecimal());
            int comparison = cumulative.compareTo(event.orderQuantity().asBigDecimal());
            if (comparison > 0) {
                throw new IllegalStateException("order overfill exceeds orderQuantity");
            }
            if (event.type() == OrderLifecycleContractV1.EventType.PARTIALLY_FILLED && comparison >= 0) {
                throw new IllegalStateException("partial fill must leave remaining quantity");
            }
            if (event.type() == OrderLifecycleContractV1.EventType.FILLED && comparison != 0) {
                throw new IllegalStateException("final fill must complete orderQuantity");
            }
        }
    }

    private void applyLifecycle(UUID aggregateId, OrderLifecycleContractV1.Event event) {
        orderStateByAggregate.put(aggregateId, event.type());
        orderQuantityByAggregate.putIfAbsent(aggregateId, event.orderQuantity().asBigDecimal());
        if (event.fillQuantity() != null) {
            filledQuantityByAggregate.merge(aggregateId, event.fillQuantity().asBigDecimal(), BigDecimal::add);
        }
    }

    private static boolean isTerminal(OrderLifecycleContractV1.EventType type) {
        return type == OrderLifecycleContractV1.EventType.FILLED
            || type == OrderLifecycleContractV1.EventType.CANCELLED
            || type == OrderLifecycleContractV1.EventType.EXPIRED
            || type == OrderLifecycleContractV1.EventType.REJECTED;
    }

    public int tradeCount() {
        return tradeCount;
    }

    public int ledgerEntryCount() {
        return ledgerEntryCount;
    }

    public record DeliveryScenario(
        List<UUID> deliveryEventIds,
        int expectedTradeCount,
        int expectedLedgerEntryCount,
        DeliveryResult duplicateResult,
        DeliveryResult staleResult,
        String gapError
    ) {
        public DeliveryScenario {
            deliveryEventIds = ContractValidationV1.required(deliveryEventIds, "deliveryEventIds");
            var uniqueEventIds = new HashSet<UUID>();
            for (UUID deliveryEventId : deliveryEventIds) {
                ContractValidationV1.required(deliveryEventId, "deliveryEventId");
                if (!uniqueEventIds.add(deliveryEventId)) {
                    throw new IllegalArgumentException("duplicate deliveryEventId");
                }
            }
            deliveryEventIds = List.copyOf(deliveryEventIds);
            if (expectedTradeCount < 0) {
                throw new IllegalArgumentException("expectedTradeCount must be non-negative");
            }
            if (expectedLedgerEntryCount < 0) {
                throw new IllegalArgumentException("expectedLedgerEntryCount must be non-negative");
            }
            ContractValidationV1.required(duplicateResult, "duplicateResult");
            ContractValidationV1.required(staleResult, "staleResult");
            ContractValidationV1.requiredText(gapError, "gapError");
        }
    }
}
