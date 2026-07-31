package com.idea2strategy.trading.messaging.contract.v1;

import org.junit.jupiter.api.Test;

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
}
