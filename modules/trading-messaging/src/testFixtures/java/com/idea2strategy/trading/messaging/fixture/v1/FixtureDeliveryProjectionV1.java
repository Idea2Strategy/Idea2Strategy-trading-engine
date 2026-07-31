package com.idea2strategy.trading.messaging.fixture.v1;

import com.idea2strategy.trading.messaging.contract.v1.ContractValidationV1;
import com.idea2strategy.trading.messaging.contract.v1.OrderLifecycleContractV1;
import com.idea2strategy.trading.messaging.contract.v1.TradingEnvelopeV1;

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

        appliedEventIds.add(envelope.eventId());
        latestVersionByAggregate.put(envelope.aggregateId(), envelope.aggregateVersion());
        if (envelope.payload() instanceof OrderLifecycleContractV1.Event event) {
            if (event.ledgerTransaction() != null) {
                tradeCount++;
                ledgerEntryCount += event.ledgerTransaction().entries().size();
            }
        }
        return DeliveryResult.APPLIED;
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
            deliveryEventIds = List.copyOf(ContractValidationV1.required(deliveryEventIds, "deliveryEventIds"));
            ContractValidationV1.required(duplicateResult, "duplicateResult");
            ContractValidationV1.required(staleResult, "staleResult");
            ContractValidationV1.requiredText(gapError, "gapError");
        }
    }
}
