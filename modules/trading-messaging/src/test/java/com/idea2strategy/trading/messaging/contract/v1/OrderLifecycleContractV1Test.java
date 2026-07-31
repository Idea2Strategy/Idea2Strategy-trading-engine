package com.idea2strategy.trading.messaging.contract.v1;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderLifecycleContractV1Test {

    @Test
    void requiresCompleteFillPayloadForPartialAndFinalFills() {
        assertThatThrownBy(() -> new OrderLifecycleContractV1.Event(
            UUID.fromString("71111111-1111-1111-1111-111111111111"),
            OrderLifecycleContractV1.EventType.PARTIALLY_FILLED,
            null,
            null,
            null,
            null
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("fill");
    }

    @Test
    void permitsNoFillPayloadForCancellationWithReasonCode() {
        assertThatCode(() -> new OrderLifecycleContractV1.Event(
            UUID.fromString("71111111-1111-1111-1111-111111111111"),
            OrderLifecycleContractV1.EventType.CANCELLED,
            null,
            null,
            null,
            "USER_REQUESTED"
        )).doesNotThrowAnyException();
    }

    @Test
    void rejectsFillPayloadOnRejectedEventsAndMissingTerminalReasonCodes() {
        assertThatThrownBy(() -> new OrderLifecycleContractV1.Event(
            UUID.fromString("71111111-1111-1111-1111-111111111111"),
            OrderLifecycleContractV1.EventType.REJECTED,
            new DecimalValueV1("1"),
            new CurrencyAmountV1("USD", new DecimalValueV1("10")),
            null,
            "INSUFFICIENT_BUYING_POWER"
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("fill");

        assertThatThrownBy(() -> new OrderLifecycleContractV1.Event(
            UUID.fromString("71111111-1111-1111-1111-111111111111"),
            OrderLifecycleContractV1.EventType.EXPIRED,
            null,
            null,
            null,
            null
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("reasonCode");
    }

    @Test
    void acceptsLifecycleFillAndReasonPayloadMatrix() {
        for (var eventCase : List.of(
            new EventCase(OrderLifecycleContractV1.EventType.ACCEPTED, false, null),
            new EventCase(OrderLifecycleContractV1.EventType.PARTIALLY_FILLED, true, null),
            new EventCase(OrderLifecycleContractV1.EventType.FILLED, true, null),
            new EventCase(OrderLifecycleContractV1.EventType.CANCELLED, false, "USER_REQUESTED"),
            new EventCase(OrderLifecycleContractV1.EventType.EXPIRED, false, "SESSION_ENDED"),
            new EventCase(OrderLifecycleContractV1.EventType.REJECTED, false, "INSUFFICIENT_BUYING_POWER")
        )) {
            assertThatCode(() -> event(eventCase.type(), eventCase.hasFillPayload(), eventCase.reasonCode()))
                .doesNotThrowAnyException();
        }
    }

    @Test
    void rejectsReasonCodesForAcceptedAndFillLifecycleEvents() {
        for (var eventType : List.of(
            OrderLifecycleContractV1.EventType.ACCEPTED,
            OrderLifecycleContractV1.EventType.PARTIALLY_FILLED,
            OrderLifecycleContractV1.EventType.FILLED
        )) {
            var hasFillPayload = eventType != OrderLifecycleContractV1.EventType.ACCEPTED;
            assertThatThrownBy(() -> event(eventType, hasFillPayload, "UNEXPECTED_REASON"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reasonCode");
        }
    }

    @Test
    void rejectsFillPayloadForEveryNonFillLifecycleEvent() {
        for (var eventType : List.of(
            OrderLifecycleContractV1.EventType.ACCEPTED,
            OrderLifecycleContractV1.EventType.CANCELLED,
            OrderLifecycleContractV1.EventType.EXPIRED,
            OrderLifecycleContractV1.EventType.REJECTED
        )) {
            var reasonCode = eventType == OrderLifecycleContractV1.EventType.ACCEPTED ? null : "TERMINAL_REASON";
            assertThatThrownBy(() -> event(eventType, true, reasonCode))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fill");
        }
    }

    @Test
    void requiresReasonCodeForEveryTerminalNonFillLifecycleEvent() {
        for (var eventType : List.of(
            OrderLifecycleContractV1.EventType.CANCELLED,
            OrderLifecycleContractV1.EventType.EXPIRED,
            OrderLifecycleContractV1.EventType.REJECTED
        )) {
            assertThatThrownBy(() -> event(eventType, false, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reasonCode");
        }
    }

    private OrderLifecycleContractV1.Event event(
        OrderLifecycleContractV1.EventType type,
        boolean hasFillPayload,
        String reasonCode
    ) {
        return new OrderLifecycleContractV1.Event(
            UUID.fromString("71111111-1111-1111-1111-111111111111"),
            type,
            hasFillPayload ? new DecimalValueV1("1") : null,
            hasFillPayload ? new CurrencyAmountV1("USD", new DecimalValueV1("10")) : null,
            hasFillPayload ? ledgerTransaction() : null,
            reasonCode
        );
    }

    private LedgerContractV1.Transaction ledgerTransaction() {
        var sourceEventId = UUID.fromString("72222222-2222-2222-2222-222222222222");
        var debit = new LedgerContractV1.Entry(
            UUID.fromString("73333333-3333-3333-3333-333333333333"),
            "CASH",
            LedgerContractV1.Direction.DEBIT,
            new CurrencyAmountV1("USD", new DecimalValueV1("10")),
            sourceEventId
        );
        var credit = new LedgerContractV1.Entry(
            UUID.fromString("74444444-4444-4444-4444-444444444444"),
            "EXECUTED_ORDERS",
            LedgerContractV1.Direction.CREDIT,
            new CurrencyAmountV1("USD", new DecimalValueV1("10")),
            sourceEventId
        );
        return new LedgerContractV1.Transaction(
            UUID.fromString("75555555-5555-5555-5555-555555555555"),
            sourceEventId,
            Instant.parse("2026-07-31T00:00:00Z"),
            List.of(debit, credit)
        );
    }

    private record EventCase(OrderLifecycleContractV1.EventType type, boolean hasFillPayload, String reasonCode) {
    }
}
