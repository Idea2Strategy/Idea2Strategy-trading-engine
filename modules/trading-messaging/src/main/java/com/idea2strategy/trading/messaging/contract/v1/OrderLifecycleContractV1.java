package com.idea2strategy.trading.messaging.contract.v1;

import java.util.UUID;

public final class OrderLifecycleContractV1 {
    private OrderLifecycleContractV1() {
    }

    public enum EventType { ACCEPTED, PARTIALLY_FILLED, FILLED, CANCELLED, EXPIRED, REJECTED }

    public record Event(
        UUID orderId,
        EventType type,
        DecimalValueV1 fillQuantity,
        CurrencyAmountV1 fillPrice,
        LedgerContractV1.Transaction ledgerTransaction
    ) {
        public Event {
            ContractValidationV1.required(orderId, "orderId");
            ContractValidationV1.required(type, "type");
            ContractValidationV1.required(fillQuantity, "fillQuantity");
            ContractValidationV1.required(fillPrice, "fillPrice");
            ContractValidationV1.required(ledgerTransaction, "ledgerTransaction");
        }
    }
}
