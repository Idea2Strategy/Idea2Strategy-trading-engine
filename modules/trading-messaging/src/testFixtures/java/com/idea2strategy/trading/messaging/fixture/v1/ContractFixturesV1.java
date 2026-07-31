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
    public static final UUID CANCELLED_ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000402");
    public static final UUID REJECTED_ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000403");
    public static final UUID PARTIAL_FILL_EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000501");
    public static final UUID LEDGER_TRANSACTION_ID = UUID.fromString("00000000-0000-0000-0000-000000000601");
    public static final UUID LEDGER_PUBLICATION_TRANSACTION_ID = UUID.fromString("00000000-0000-0000-0000-000000000604");
    public static final UUID SETTLEMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000701");
    public static final Instant FIXTURE_TIME = Instant.parse("2026-07-31T14:30:00Z");
    public static final String COST_POLICY_VERSION = "virtual-fill-cost-v1";

    public static final UUID CORRELATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000801");
    public static final UUID CAUSATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000802");
    public static final UUID ACCEPTED_EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000411");
    public static final UUID STALE_ACCEPTED_EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000412");
    public static final UUID FUTURE_GAP_EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000413");
    public static final UUID FILLED_EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000502");
    public static final UUID CANCELLATION_ACCEPTED_EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000414");
    public static final UUID CANCELLED_EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000503");
    public static final UUID REJECTED_EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000504");
    public static final UUID SETTLEMENT_REQUESTED_EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000702");
    public static final UUID SETTLEMENT_FAILED_EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000703");
    public static final UUID SETTLEMENT_COMPLETED_EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000704");
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
            "order.intent-batch", INTENT_BATCH_ID, "intent-batch-" + candidates.batchId(), INTENT_BATCH_ID, 1, candidates.batchId(),
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
        return envelope("order.intent-batch", INTENT_BATCH_ID, "intent-batch-" + INTENT_BATCH_ID, INTENT_BATCH_ID, 1, CANDIDATE_BATCH_ID, intentBatch);
    }

    public static TradingEnvelopeV1<OrderLifecycleContractV1.Event> acceptedEnvelope() {
        return envelope("order.accepted", ACCEPTED_EVENT_ID, "accepted-" + ACCEPTED_EVENT_ID, ORDER_ID, 1, INTENT_BATCH_ID,
            lifecycleEvent(ORDER_ID, "00000000-0000-0000-0000-000000000311", "00000000-0000-0000-0000-000000000211", "10.5",
                OrderLifecycleContractV1.EventType.ACCEPTED, null, null, null, null));
    }

    public static TradingEnvelopeV1<OrderLifecycleContractV1.Event> staleAcceptedEnvelope() {
        return envelope("order.accepted", STALE_ACCEPTED_EVENT_ID, "stale-accepted-" + STALE_ACCEPTED_EVENT_ID, ORDER_ID, 1, INTENT_BATCH_ID,
            acceptedEnvelope().payload());
    }

    public static TradingEnvelopeV1<OrderLifecycleContractV1.Event> futureGapEnvelope() {
        return envelope("order.accepted", FUTURE_GAP_EVENT_ID, "future-gap-" + FUTURE_GAP_EVENT_ID, ORDER_ID, 3, INTENT_BATCH_ID,
            acceptedEnvelope().payload());
    }

    public static TradingEnvelopeV1<OrderLifecycleContractV1.Event> partialFillEnvelope() {
        var transaction = ledgerTransaction(LEDGER_TRANSACTION_ID, PARTIAL_FILL_EVENT_ID, "250", "SECURITY", "CASH");
        var event = new OrderLifecycleContractV1.Event(
            ORDER_ID, UUID.fromString("00000000-0000-0000-0000-000000000311"), UUID.fromString("00000000-0000-0000-0000-000000000211"),
            OrderLifecycleContractV1.EventType.PARTIALLY_FILLED, new DecimalValueV1("10.5"),
            new DecimalValueV1("2.5"), new CurrencyAmountV1("USD", new DecimalValueV1("100")), transaction, null
        );
        return envelope("order.partially-filled", PARTIAL_FILL_EVENT_ID, "partial-fill-" + PARTIAL_FILL_EVENT_ID, ORDER_ID, 2, ACCEPTED_EVENT_ID, event);
    }

    public static TradingEnvelopeV1<OrderLifecycleContractV1.Event> filledEnvelope() {
        var transaction = ledgerTransaction(UUID.fromString("00000000-0000-0000-0000-000000000602"), FILLED_EVENT_ID, "800", "SECURITY", "CASH");
        var event = new OrderLifecycleContractV1.Event(
            ORDER_ID, UUID.fromString("00000000-0000-0000-0000-000000000311"), UUID.fromString("00000000-0000-0000-0000-000000000211"),
            OrderLifecycleContractV1.EventType.FILLED, new DecimalValueV1("10.5"),
            new DecimalValueV1("8"), new CurrencyAmountV1("USD", new DecimalValueV1("100")), transaction, null
        );
        return envelope("order.filled", FILLED_EVENT_ID, "filled-" + FILLED_EVENT_ID, ORDER_ID, 3, PARTIAL_FILL_EVENT_ID, event);
    }

    public static TradingEnvelopeV1<OrderLifecycleContractV1.Event> cancellationAcceptedEnvelope() {
        var event = lifecycleEvent(
            CANCELLED_ORDER_ID, "00000000-0000-0000-0000-000000000312", "00000000-0000-0000-0000-000000000212", "10",
            OrderLifecycleContractV1.EventType.ACCEPTED, null, null, null, null
        );
        return envelope(
            "order.accepted", CANCELLATION_ACCEPTED_EVENT_ID, "accepted-" + CANCELLATION_ACCEPTED_EVENT_ID,
            CANCELLED_ORDER_ID, 1, INTENT_BATCH_ID, event
        );
    }

    public static TradingEnvelopeV1<OrderLifecycleContractV1.Event> cancelledEnvelope() {
        var event = lifecycleEvent(CANCELLED_ORDER_ID, "00000000-0000-0000-0000-000000000312", "00000000-0000-0000-0000-000000000212", "10",
            OrderLifecycleContractV1.EventType.CANCELLED, null, null, null, "USER_REQUESTED");
        return envelope("order.cancelled", CANCELLED_EVENT_ID, "cancelled-" + CANCELLED_EVENT_ID, CANCELLED_ORDER_ID, 2, CANCELLATION_ACCEPTED_EVENT_ID, event);
    }

    public static TradingEnvelopeV1<OrderLifecycleContractV1.Event> rejectedEnvelope() {
        var event = lifecycleEvent(REJECTED_ORDER_ID, "00000000-0000-0000-0000-000000000313", "00000000-0000-0000-0000-000000000213", "5",
            OrderLifecycleContractV1.EventType.REJECTED, null, null, null, "INSUFFICIENT_BUYING_POWER");
        return envelope("order.rejected", REJECTED_EVENT_ID, "rejected-" + REJECTED_EVENT_ID, REJECTED_ORDER_ID, 1, INTENT_BATCH_ID, event);
    }

    public static TradingEnvelopeV1<SettlementContractV1.Event> settlementRequestedEnvelope() {
        var event = new SettlementContractV1.Event(SETTLEMENT_ID, BOT_ID, SettlementContractV1.EventType.REQUESTED, null, 1, List.of(ORDER_ID));
        return envelopeAt(
            "settlement.requested", SETTLEMENT_REQUESTED_EVENT_ID, "settlement-" + SETTLEMENT_ID + "-request",
            SETTLEMENT_ID, 1, FILLED_EVENT_ID, FIXTURE_TIME.plusSeconds(1), event
        );
    }

    public static TradingEnvelopeV1<SettlementContractV1.Event> settlementFailedEnvelope() {
        var event = new SettlementContractV1.Event(SETTLEMENT_ID, BOT_ID, SettlementContractV1.EventType.FAILED, "CLEARING_TIMEOUT", 1, List.of(ORDER_ID));
        return envelopeAt(
            "settlement.failed", SETTLEMENT_FAILED_EVENT_ID, "settlement-" + SETTLEMENT_ID + "-attempt-1",
            SETTLEMENT_ID, 2, SETTLEMENT_REQUESTED_EVENT_ID, FIXTURE_TIME.plusSeconds(2), event
        );
    }

    public static TradingEnvelopeV1<SettlementContractV1.Event> settlementCompletedEnvelope() {
        var event = new SettlementContractV1.Event(SETTLEMENT_ID, BOT_ID, SettlementContractV1.EventType.COMPLETED, null, 2, List.of(ORDER_ID));
        return envelopeAt(
            "settlement.completed", SETTLEMENT_COMPLETED_EVENT_ID, "settlement-" + SETTLEMENT_ID + "-attempt-2",
            SETTLEMENT_ID, 3, SETTLEMENT_FAILED_EVENT_ID, FIXTURE_TIME.plusSeconds(3), event
        );
    }

    public static TradingEnvelopeV1<LedgerContractV1.Transaction> ledgerTransactionEnvelope() {
        return envelope("ledger.transaction", LEDGER_ENVELOPE_EVENT_ID, "ledger-transaction-" + LEDGER_PUBLICATION_TRANSACTION_ID,
            LEDGER_PUBLICATION_TRANSACTION_ID, 1, PARTIAL_FILL_EVENT_ID,
            ledgerTransaction(LEDGER_PUBLICATION_TRANSACTION_ID, LEDGER_ENVELOPE_EVENT_ID, "250", "SECURITY", "CASH"));
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

    private static OrderLifecycleContractV1.Event lifecycleEvent(
        UUID orderId,
        String intentId,
        String candidateId,
        String orderQuantity,
        OrderLifecycleContractV1.EventType type,
        DecimalValueV1 fillQuantity,
        CurrencyAmountV1 fillPrice,
        LedgerContractV1.Transaction transaction,
        String reasonCode
    ) {
        return new OrderLifecycleContractV1.Event(
            orderId, UUID.fromString(intentId), UUID.fromString(candidateId), type, new DecimalValueV1(orderQuantity),
            fillQuantity, fillPrice, transaction, reasonCode
        );
    }

    private static UUID entryId(UUID transactionId, int sequence) {
        String entrySuffix = transactionId.equals(LEDGER_TRANSACTION_ID) ? "61"
            : transactionId.equals(LEDGER_PUBLICATION_TRANSACTION_ID) ? "64" : "62";
        return UUID.fromString("00000000-0000-0000-0000-000000000" + entrySuffix + sequence);
    }

    private static <T> TradingEnvelopeV1<T> envelope(
        String eventType,
        UUID eventId,
        String idempotencyKey,
        UUID aggregateId,
        long aggregateVersion,
        UUID causationId,
        T payload
    ) {
        return envelopeAt(eventType, eventId, idempotencyKey, aggregateId, aggregateVersion, causationId, FIXTURE_TIME, payload);
    }

    private static <T> TradingEnvelopeV1<T> envelopeAt(
        String eventType,
        UUID eventId,
        String idempotencyKey,
        UUID aggregateId,
        long aggregateVersion,
        UUID causationId,
        Instant occurredAt,
        T payload
    ) {
        return new TradingEnvelopeV1<>(
            "trading.v1", eventType, eventId, occurredAt, "trading-worker", CORRELATION_ID, causationId,
            idempotencyKey, aggregateId, aggregateVersion, payload
        );
    }
}
