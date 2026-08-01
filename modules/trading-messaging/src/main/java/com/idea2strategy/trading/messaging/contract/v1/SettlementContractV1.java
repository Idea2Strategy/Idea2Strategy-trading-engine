package com.idea2strategy.trading.messaging.contract.v1;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public final class SettlementContractV1 {
    private SettlementContractV1() {
    }

    public enum EventType { REQUESTED, COMPLETED, FAILED }

    public record Event(
        UUID settlementId,
        UUID botId,
        EventType type,
        String reasonCode,
        int attempt,
        List<UUID> affectedOrderIds
    ) {
        public Event {
            ContractValidationV1.required(settlementId, "settlementId");
            ContractValidationV1.required(botId, "botId");
            ContractValidationV1.required(type, "type");
            if (attempt < 1) {
                throw new IllegalArgumentException("attempt must be at least one");
            }
            if (type == EventType.FAILED) {
                ContractValidationV1.requiredText(reasonCode, "reasonCode");
            } else if (reasonCode != null) {
                throw new IllegalArgumentException("reasonCode is allowed only for FAILED settlements");
            }
            affectedOrderIds = ContractValidationV1.required(affectedOrderIds, "affectedOrderIds");
            var seenOrderIds = new HashSet<UUID>();
            for (UUID affectedOrderId : affectedOrderIds) {
                ContractValidationV1.required(affectedOrderId, "affectedOrderId");
                if (!seenOrderIds.add(affectedOrderId)) {
                    throw new IllegalArgumentException("duplicate affectedOrderId");
                }
            }
            affectedOrderIds = List.copyOf(affectedOrderIds);
        }
    }
}
