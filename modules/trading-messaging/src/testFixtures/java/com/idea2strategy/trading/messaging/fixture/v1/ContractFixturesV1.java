package com.idea2strategy.trading.messaging.fixture.v1;

import com.idea2strategy.trading.messaging.contract.v1.CurrencyAmountV1;
import com.idea2strategy.trading.messaging.contract.v1.DecimalValueV1;
import com.idea2strategy.trading.messaging.contract.v1.LedgerContractV1;
import com.idea2strategy.trading.messaging.contract.v1.OrderLifecycleContractV1;
import com.idea2strategy.trading.messaging.contract.v1.TradingEnvelopeV1;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ContractFixturesV1 {
    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORDER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID AGGREGATE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant OCCURRED_AT = Instant.parse("2026-07-31T00:00:00Z");

    private ContractFixturesV1() {
    }

    public static TradingEnvelopeV1<OrderLifecycleContractV1.Event> partialFillEnvelope() {
        var amount = new CurrencyAmountV1("USD", new DecimalValueV1("100"));
        var transaction = new LedgerContractV1.Transaction(
            UUID.fromString("44444444-4444-4444-4444-444444444444"),
            EVENT_ID,
            OCCURRED_AT,
            List.of(
                new LedgerContractV1.Entry(
                    UUID.fromString("55555555-5555-5555-5555-555555555555"),
                    "CASH",
                    LedgerContractV1.Direction.DEBIT,
                    amount,
                    EVENT_ID
                ),
                new LedgerContractV1.Entry(
                    UUID.fromString("66666666-6666-6666-6666-666666666666"),
                    "EXECUTED_ORDERS",
                    LedgerContractV1.Direction.CREDIT,
                    amount,
                    EVENT_ID
                )
            )
        );
        var event = new OrderLifecycleContractV1.Event(
            ORDER_ID,
            OrderLifecycleContractV1.EventType.PARTIALLY_FILLED,
            new DecimalValueV1("1"),
            amount,
            transaction
        );

        return new TradingEnvelopeV1<>(
            "trading-envelope.v1",
            "order.partially-filled",
            EVENT_ID,
            OCCURRED_AT,
            "trading-engine",
            UUID.fromString("77777777-7777-7777-7777-777777777777"),
            UUID.fromString("88888888-8888-8888-8888-888888888888"),
            "partial-fill-1",
            AGGREGATE_ID,
            1,
            event
        );
    }
}
