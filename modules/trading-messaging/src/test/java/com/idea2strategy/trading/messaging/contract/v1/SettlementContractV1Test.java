package com.idea2strategy.trading.messaging.contract.v1;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementContractV1Test {

    @Test
    void failedSettlementRequiresNonblankReasonCode() {
        assertThatThrownBy(() -> event(SettlementContractV1.EventType.FAILED, " ", 1, List.of(id("00000000-0000-0000-0000-000000000401"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("reasonCode");
    }

    @Test
    void nonFailedSettlementDoesNotAllowReasonCode() {
        assertThatThrownBy(() -> event(SettlementContractV1.EventType.COMPLETED, "UNEXPECTED", 1, List.of(id("00000000-0000-0000-0000-000000000401"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("reasonCode");
    }

    @Test
    void settlementRequiresPositiveAttempt() {
        assertThatThrownBy(() -> event(SettlementContractV1.EventType.COMPLETED, null, 0, List.of(id("00000000-0000-0000-0000-000000000401"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("attempt");
    }

    @Test
    void settlementRejectsNullAffectedOrderId() {
        assertThatThrownBy(() -> event(SettlementContractV1.EventType.COMPLETED, null, 1, java.util.Arrays.asList(id("00000000-0000-0000-0000-000000000401"), null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("affectedOrderId");
    }

    @Test
    void settlementRejectsDuplicateAffectedOrderIds() {
        var orderId = id("00000000-0000-0000-0000-000000000401");

        assertThatThrownBy(() -> event(SettlementContractV1.EventType.COMPLETED, null, 1, List.of(orderId, orderId)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("duplicate affectedOrderId");
    }

    @Test
    void settlementDefensivelyCopiesAffectedOrderIds() {
        var orderId = id("00000000-0000-0000-0000-000000000401");
        var suppliedOrderIds = new ArrayList<>(List.of(orderId));
        var event = event(SettlementContractV1.EventType.COMPLETED, null, 1, suppliedOrderIds);

        suppliedOrderIds.clear();

        assertThat(event.affectedOrderIds()).containsExactly(orderId);
    }

    private SettlementContractV1.Event event(
        SettlementContractV1.EventType type,
        String reasonCode,
        int attempt,
        List<UUID> affectedOrderIds
    ) {
        return new SettlementContractV1.Event(
            id("00000000-0000-0000-0000-000000000701"),
            id("00000000-0000-0000-0000-000000000101"),
            type,
            reasonCode,
            attempt,
            affectedOrderIds
        );
    }

    private UUID id(String value) {
        return UUID.fromString(value);
    }
}
