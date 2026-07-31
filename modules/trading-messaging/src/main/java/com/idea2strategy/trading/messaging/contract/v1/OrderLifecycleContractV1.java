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
        LedgerContractV1.Transaction ledgerTransaction,
        String reasonCode
    ) {
        public Event {
            ContractValidationV1.required(orderId, "orderId");
            ContractValidationV1.required(type, "type");
            if (isFill(type)) {
                ContractValidationV1.required(fillQuantity, "fillQuantity");
                ContractValidationV1.required(fillPrice, "fillPrice");
                ContractValidationV1.required(ledgerTransaction, "ledgerTransaction");
            } else if (fillQuantity != null || fillPrice != null || ledgerTransaction != null) {
                throw new IllegalArgumentException("fill fields are allowed only for partial and final fills");
            }
            if (requiresReasonCode(type)) {
                ContractValidationV1.requiredText(reasonCode, "reasonCode");
            }
        }

        public Event(
            UUID orderId,
            EventType type,
            DecimalValueV1 fillQuantity,
            CurrencyAmountV1 fillPrice,
            LedgerContractV1.Transaction ledgerTransaction
        ) {
            this(orderId, type, fillQuantity, fillPrice, ledgerTransaction, null);
        }

        private static boolean isFill(EventType type) {
            return type == EventType.PARTIALLY_FILLED || type == EventType.FILLED;
        }

        private static boolean requiresReasonCode(EventType type) {
            return type == EventType.CANCELLED || type == EventType.EXPIRED || type == EventType.REJECTED;
        }
    }
}
