package com.idea2strategy.trading.messaging.fixture.v1;

import com.idea2strategy.trading.messaging.contract.v1.CurrencyAmountV1;
import com.idea2strategy.trading.messaging.contract.v1.DecimalValueV1;
import com.idea2strategy.trading.messaging.contract.v1.LedgerContractV1;
import com.idea2strategy.trading.messaging.contract.v1.OrderExecutionContractV1;
import com.idea2strategy.trading.messaging.contract.v1.OrderLifecycleContractV1;
import com.idea2strategy.trading.messaging.contract.v1.SettlementContractV1;
import com.idea2strategy.trading.messaging.contract.v1.TradingEnvelopeV1;
import com.idea2strategy.trading.messaging.evaluation.OrderCandidate;
import com.idea2strategy.trading.messaging.evaluation.OrderCandidateBatch;
import com.idea2strategy.trading.messaging.evaluation.OrderSide;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ContractFixturesV1 {
    public static final UUID BOT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    public static final UUID STRATEGY_VERSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000102");
    public static final UUID EVALUATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000103");
    public static final UUID INSTRUMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000104");
    public static final UUID CANDIDATE_BATCH_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    public static final UUID INTENT_BATCH_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");
    public static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000401");
    public static final UUID PARTIAL_FILL_EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000501");
    public static final UUID LEDGER_TRANSACTION_ID = UUID.fromString("00000000-0000-0000-0000-000000000601");
    public static final UUID SETTLEMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000701");
    public static final Instant FIXTURE_TIME = Instant.parse("2026-07-31T14:30:00Z");
    public static final String COST_POLICY_VERSION = "virtual-fill-cost-v1";

    public static final UUID CORRELATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000801");
    public static final UUID CAUSATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000802");
    public static final UUID ACCEPTED_EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000411");
    public static final UUID STALE_ACCEPTED_EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000412");
    public static final UUID FUTURE_GAP_EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000413");
    public static final UUID FILLED_EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000502");
    public static final UUID CANCELLED_EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000503");
    public static final UUID REJECTED_EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000504");
    public static final UUID SETTLEMENT_EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000702");
    public static final UUID LEDGER_ENVELOPE_EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000603");

    private ContractFixturesV1() {
    }

    public static OrderCandidateBatch candidateBatch() {
        return new OrderCandidateBatch(
            1, CANDIDATE_BATCH_ID, EVALUATION_ID, FIXTURE_TIME,
            List.of(
                candidate("00000000-0000-0000-0000-000000000211", OrderSide.BUY, "10.5", null, "MOMENTUM_SIGNAL"),
                candidate("00000000-0000-0000-0000-000000000212", OrderSide.BUY, "20", null, "RISK_ADJUSTED"),
                candidate("00000000-0000-0000-0000-000000000213", OrderSide.SELL, "5", null, "REBALANCE_SIGNAL")
            )
        );
    }

    public static TradingEnvelopeV1<OrderExecutionContractV1.IntentBatch> intentBatchFor(OrderCandidateBatch candidates) {
        var intents = candidates.candidates().stream()
            .map(candidate -> new OrderExecutionContractV1.Intent(
                UUID.nameUUIDFromBytes(("intent-" + candidate.candidateId()).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                candidate.candidateId(), candidate.instrumentId(), side(candidate.side()),
                candidate.limitPrice() == null ? OrderExecutionContractV1.OrderType.MARKET : OrderExecutionContractV1.OrderType.LIMIT,
                candidate.limitPrice() == null ? marketParameters() : limitParameters(decimal(candidate.limitPrice())),
                OrderExecutionContractV1.TimeInForce.DAY, null, OrderExecutionContractV1.QuantityMode.WHOLE_SHARES,
                decimal(candidate.quantity()), decimal(candidate.quantity()), OrderExecutionContractV1.IntentDecision.ACCEPTED,
                null, costPolicy()
            ))
            .toList();
        return envelope(
            "order.intent-batch", INTENT_BATCH_ID, "intent-batch-" + candidates.batchId(), INTENT_BATCH_ID, 1,
            new OrderExecutionContractV1.IntentBatch(INTENT_BATCH_ID, BOT_ID, candidates.evaluationId(), intents)
        );
    }

    public static TradingEnvelopeV1<OrderExecutionContractV1.IntentBatch> intentBatchEnvelope() {
        var costPolicy = costPolicy();
        var intentBatch = new OrderExecutionContractV1.IntentBatch(
            INTENT_BATCH_ID, BOT_ID, EVALUATION_ID,
            List.of(
                intent("00000000-0000-0000-0000-000000000311", "00000000-0000-0000-0000-000000000211", OrderExecutionContractV1.Side.BUY, "10.5", "10.5", OrderExecutionContractV1.IntentDecision.ACCEPTED, null, OrderExecutionContractV1.QuantityMode.FRACTIONAL_SHARES, costPolicy),
                intent("00000000-0000-0000-0000-000000000312", "00000000-0000-0000-0000-000000000212", OrderExecutionContractV1.Side.BUY, "20", "10", OrderExecutionContractV1.IntentDecision.REDUCED, "RISK_LIMIT", OrderExecutionContractV1.QuantityMode.WHOLE_SHARES, costPolicy),
                intent("00000000-0000-0000-0000-000000000313", "00000000-0000-0000-0000-000000000213", OrderExecutionContractV1.Side.SELL, "5", "0", OrderExecutionContractV1.IntentDecision.REJECTED, "POSITION_CONSTRAINT", OrderExecutionContractV1.QuantityMode.WHOLE_SHARES, costPolicy)
            )
        );
        return envelope("order.intent-batch", INTENT_BATCH_ID, "intent-batch-" + INTENT_BATCH_ID, INTENT_BATCH_ID, 1, intentBatch);
    }

    public static TradingEnvelopeV1<OrderLifecycleContractV1.Event> acceptedEnvelope() {
        return envelope("order.accepted", ACCEPTED_EVENT_ID, "accepted-" + ACCEPTED_EVENT_ID, ORDER_ID, 1,
            new OrderLifecycleContractV1.Event(ORDER_ID, OrderLifecycleContractV1.EventType.ACCEPTED, null, null, null, null));
    }

    public static TradingEnvelopeV1<OrderLifecycleContractV1.Event> staleAcceptedEnvelope() {
        return envelope("order.accepted", STALE_ACCEPTED_EVENT_ID, "stale-accepted-" + STALE_ACCEPTED_EVENT_ID, ORDER_ID, 1,
            acceptedEnvelope().payload());
    }

    public static TradingEnvelopeV1<OrderLifecycleContractV1.Event> futureGapEnvelope() {
        return envelope("order.accepted", FUTURE_GAP_EVENT_ID, "future-gap-" + FUTURE_GAP_EVENT_ID, ORDER_ID, 3,
            acceptedEnvelope().payload());
    }

    public static TradingEnvelopeV1<OrderLifecycleContractV1.Event> partialFillEnvelope() {
        var transaction = ledgerTransaction(LEDGER_TRANSACTION_ID, PARTIAL_FILL_EVENT_ID, "250", "SECURITY", "CASH");
        var event = new OrderLifecycleContractV1.Event(
            ORDER_ID, OrderLifecycleContractV1.EventType.PARTIALLY_FILLED,
            new DecimalValueV1("2.5"), new CurrencyAmountV1("USD", new DecimalValueV1("100")), transaction, null
        );
        return envelope("order.partially-filled", PARTIAL_FILL_EVENT_ID, "partial-fill-" + PARTIAL_FILL_EVENT_ID, ORDER_ID, 1, event);
    }

    public static TradingEnvelopeV1<OrderLifecycleContractV1.Event> filledEnvelope() {
        var transaction = ledgerTransaction(UUID.fromString("00000000-0000-0000-0000-000000000602"), FILLED_EVENT_ID, "750", "SECURITY", "CASH");
        var event = new OrderLifecycleContractV1.Event(
            ORDER_ID, OrderLifecycleContractV1.EventType.FILLED,
            new DecimalValueV1("7.5"), new CurrencyAmountV1("USD", new DecimalValueV1("100")), transaction, null
        );
        return envelope("order.filled", FILLED_EVENT_ID, "filled-" + FILLED_EVENT_ID, ORDER_ID, 2, event);
    }

    public static TradingEnvelopeV1<OrderLifecycleContractV1.Event> cancelledEnvelope() {
        var event = new OrderLifecycleContractV1.Event(ORDER_ID, OrderLifecycleContractV1.EventType.CANCELLED, null, null, null, "USER_REQUESTED");
        return envelope("order.cancelled", CANCELLED_EVENT_ID, "cancelled-" + CANCELLED_EVENT_ID, ORDER_ID, 3, event);
    }

    public static TradingEnvelopeV1<OrderLifecycleContractV1.Event> rejectedEnvelope() {
        var event = new OrderLifecycleContractV1.Event(ORDER_ID, OrderLifecycleContractV1.EventType.REJECTED, null, null, null, "INSUFFICIENT_BUYING_POWER");
        return envelope("order.rejected", REJECTED_EVENT_ID, "rejected-" + REJECTED_EVENT_ID, ORDER_ID, 1, event);
    }

    public static TradingEnvelopeV1<SettlementContractV1.Event> settlementCompletedEnvelope() {
        var event = new SettlementContractV1.Event(SETTLEMENT_ID, BOT_ID, SettlementContractV1.EventType.COMPLETED, null, 1, List.of(ORDER_ID));
        return envelope("settlement.completed", SETTLEMENT_EVENT_ID, "settlement-completed-" + SETTLEMENT_ID, SETTLEMENT_ID, 1, event);
    }

    public static TradingEnvelopeV1<LedgerContractV1.Transaction> ledgerTransactionEnvelope() {
        return envelope("ledger.transaction", LEDGER_ENVELOPE_EVENT_ID, "ledger-transaction-" + LEDGER_TRANSACTION_ID, LEDGER_TRANSACTION_ID, 1,
            ledgerTransaction(LEDGER_TRANSACTION_ID, PARTIAL_FILL_EVENT_ID, "250", "SECURITY", "CASH"));
    }

    public static FixtureDeliveryProjectionV1.DeliveryScenario deliveryScenario() {
        return new FixtureDeliveryProjectionV1.DeliveryScenario(
            List.of(PARTIAL_FILL_EVENT_ID), 1, 2,
            FixtureDeliveryProjectionV1.DeliveryResult.DUPLICATE,
            FixtureDeliveryProjectionV1.DeliveryResult.STALE,
            "aggregate version gap: sequence gap"
        );
    }

    private static OrderExecutionContractV1.CostPolicy costPolicy() {
        return new OrderExecutionContractV1.CostPolicy(COST_POLICY_VERSION, new DecimalValueV1("0.002"), new DecimalValueV1("0.0005"));
    }

    private static OrderCandidate candidate(String candidateId, OrderSide side, String quantity, String limitPrice, String reasonCode) {
        return new OrderCandidate(
            UUID.fromString(candidateId), INSTRUMENT_ID, side, new BigDecimal(quantity),
            limitPrice == null ? null : new BigDecimal(limitPrice), List.of(reasonCode)
        );
    }

    private static OrderExecutionContractV1.Side side(OrderSide side) {
        return side == OrderSide.BUY ? OrderExecutionContractV1.Side.BUY : OrderExecutionContractV1.Side.SELL;
    }

    private static DecimalValueV1 decimal(BigDecimal value) {
        return new DecimalValueV1(value.stripTrailingZeros().toPlainString());
    }

    private static OrderExecutionContractV1.OrderParameters marketParameters() {
        return new OrderExecutionContractV1.OrderParameters(null, null, null);
    }

    private static OrderExecutionContractV1.OrderParameters limitParameters(DecimalValueV1 limitPrice) {
        return new OrderExecutionContractV1.OrderParameters(
            new CurrencyAmountV1("USD", limitPrice), null, null
        );
    }

    private static OrderExecutionContractV1.Intent intent(
        String intentId, String candidateId, OrderExecutionContractV1.Side side, String requestedQuantity, String approvedQuantity,
        OrderExecutionContractV1.IntentDecision decision, String reasonCode, OrderExecutionContractV1.QuantityMode quantityMode,
        OrderExecutionContractV1.CostPolicy costPolicy
    ) {
        return new OrderExecutionContractV1.Intent(
            UUID.fromString(intentId), UUID.fromString(candidateId), INSTRUMENT_ID, side,
            OrderExecutionContractV1.OrderType.MARKET, marketParameters(), OrderExecutionContractV1.TimeInForce.DAY, null, quantityMode,
            new DecimalValueV1(requestedQuantity), new DecimalValueV1(approvedQuantity), decision, reasonCode, costPolicy
        );
    }

    private static LedgerContractV1.Transaction ledgerTransaction(UUID transactionId, UUID sourceEventId, String amount, String debitAccount, String creditAccount) {
        var currencyAmount = new CurrencyAmountV1("USD", new DecimalValueV1(amount));
        return new LedgerContractV1.Transaction(transactionId, sourceEventId, FIXTURE_TIME, List.of(
            new LedgerContractV1.Entry(entryId(transactionId, 1), debitAccount, LedgerContractV1.Direction.DEBIT, currencyAmount, sourceEventId),
            new LedgerContractV1.Entry(entryId(transactionId, 2), creditAccount, LedgerContractV1.Direction.CREDIT, currencyAmount, sourceEventId)
        ));
    }

    private static UUID entryId(UUID transactionId, int sequence) {
        String entrySuffix = transactionId.equals(LEDGER_TRANSACTION_ID) ? "61" : "62";
        return UUID.fromString("00000000-0000-0000-0000-000000000" + entrySuffix + sequence);
    }

    private static <T> TradingEnvelopeV1<T> envelope(String eventType, UUID eventId, String idempotencyKey, UUID aggregateId, long aggregateVersion, T payload) {
        return new TradingEnvelopeV1<>(
            "trading.v1", eventType, eventId, FIXTURE_TIME, "trading-worker", CORRELATION_ID, CAUSATION_ID,
            idempotencyKey, aggregateId, aggregateVersion, payload
        );
    }
}
