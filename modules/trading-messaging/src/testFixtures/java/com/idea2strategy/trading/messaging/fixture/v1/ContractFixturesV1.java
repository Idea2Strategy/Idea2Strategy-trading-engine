package com.idea2strategy.trading.messaging.fixture.v1;

import com.idea2strategy.trading.messaging.contract.v1.CurrencyAmountV1;
import com.idea2strategy.trading.messaging.contract.v1.DecimalValueV1;
import com.idea2strategy.trading.messaging.contract.v1.LedgerContractV1;
import com.idea2strategy.trading.messaging.contract.v1.OrderCandidateContractV1;
import com.idea2strategy.trading.messaging.contract.v1.OrderExecutionContractV1;
import com.idea2strategy.trading.messaging.contract.v1.OrderLifecycleContractV1;
import com.idea2strategy.trading.messaging.contract.v1.TradingEnvelopeV1;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ContractFixturesV1 {
    private static final UUID BOT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID STRATEGY_VERSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID EVALUATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000103");
    private static final UUID INSTRUMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000104");
    private static final UUID CANDIDATE_BATCH_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID INTENT_BATCH_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final Instant FIXTURE_TIME = Instant.parse("2026-07-31T14:30:00Z");
    private static final String COST_POLICY_VERSION = "virtual-fill-cost-v1";
    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORDER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID AGGREGATE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant OCCURRED_AT = Instant.parse("2026-07-31T00:00:00Z");

    private ContractFixturesV1() {
    }

    public static TradingEnvelopeV1<OrderCandidateContractV1.CandidateBatch> candidateBatchEnvelope() {
        var candidateBatch = new OrderCandidateContractV1.CandidateBatch(
            CANDIDATE_BATCH_ID,
            BOT_ID,
            STRATEGY_VERSION_ID,
            EVALUATION_ID,
            "2026-07-31-us-equities",
            List.of(
                candidate("00000000-0000-0000-0000-000000000211", OrderExecutionContractV1.Side.BUY, "0.5", "MOMENTUM_SIGNAL"),
                candidate("00000000-0000-0000-0000-000000000212", OrderExecutionContractV1.Side.BUY, "0.35", "RISK_ADJUSTED"),
                candidate("00000000-0000-0000-0000-000000000213", OrderExecutionContractV1.Side.SELL, "0.15", "REBALANCE_SIGNAL")
            )
        );

        return envelope(
            "order.candidate-batch",
            CANDIDATE_BATCH_ID,
            "candidate-batch-00000000-0000-0000-0000-000000000201",
            CANDIDATE_BATCH_ID,
            candidateBatch
        );
    }

    public static TradingEnvelopeV1<OrderExecutionContractV1.IntentBatch> intentBatchEnvelope() {
        var costPolicy = new OrderExecutionContractV1.CostPolicy(
            COST_POLICY_VERSION,
            new DecimalValueV1("0.002"),
            new DecimalValueV1("0.0005")
        );
        var intentBatch = new OrderExecutionContractV1.IntentBatch(
            INTENT_BATCH_ID,
            BOT_ID,
            EVALUATION_ID,
            List.of(
                intent("00000000-0000-0000-0000-000000000311", "00000000-0000-0000-0000-000000000211", "10.5", "10.5", OrderExecutionContractV1.IntentDecision.ACCEPTED, null, OrderExecutionContractV1.QuantityMode.FRACTIONAL_SHARES, costPolicy),
                intent("00000000-0000-0000-0000-000000000312", "00000000-0000-0000-0000-000000000212", "20", "10", OrderExecutionContractV1.IntentDecision.REDUCED, "RISK_LIMIT", OrderExecutionContractV1.QuantityMode.WHOLE_SHARES, costPolicy),
                intent("00000000-0000-0000-0000-000000000313", "00000000-0000-0000-0000-000000000213", "5", "0", OrderExecutionContractV1.IntentDecision.REJECTED, "POSITION_CONSTRAINT", OrderExecutionContractV1.QuantityMode.WHOLE_SHARES, costPolicy)
            )
        );

        return envelope(
            "order.intent-batch",
            INTENT_BATCH_ID,
            "intent-batch-00000000-0000-0000-0000-000000000301",
            INTENT_BATCH_ID,
            intentBatch
        );
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

    private static OrderCandidateContractV1.Candidate candidate(
        String candidateId,
        OrderExecutionContractV1.Side side,
        String requestedWeight,
        String reasonCode
    ) {
        return new OrderCandidateContractV1.Candidate(
            UUID.fromString(candidateId),
            INSTRUMENT_ID,
            side,
            new DecimalValueV1(requestedWeight),
            reasonCode
        );
    }

    private static OrderExecutionContractV1.Intent intent(
        String intentId,
        String candidateId,
        String requestedQuantity,
        String approvedQuantity,
        OrderExecutionContractV1.IntentDecision decision,
        String reasonCode,
        OrderExecutionContractV1.QuantityMode quantityMode,
        OrderExecutionContractV1.CostPolicy costPolicy
    ) {
        return new OrderExecutionContractV1.Intent(
            UUID.fromString(intentId),
            UUID.fromString(candidateId),
            INSTRUMENT_ID,
            OrderExecutionContractV1.Side.BUY,
            OrderExecutionContractV1.OrderType.MARKET,
            OrderExecutionContractV1.TimeInForce.DAY,
            null,
            quantityMode,
            new DecimalValueV1(requestedQuantity),
            new DecimalValueV1(approvedQuantity),
            decision,
            reasonCode,
            costPolicy
        );
    }

    private static <T> TradingEnvelopeV1<T> envelope(
        String eventType,
        UUID eventId,
        String idempotencyKey,
        UUID aggregateId,
        T payload
    ) {
        return new TradingEnvelopeV1<>(
            "trading.v1",
            eventType,
            eventId,
            FIXTURE_TIME,
            "trading-worker",
            BOT_ID,
            EVALUATION_ID,
            idempotencyKey,
            aggregateId,
            1,
            payload
        );
    }
}
