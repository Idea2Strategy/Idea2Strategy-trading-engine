package com.idea2strategy.trading.persistence.projection;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
public class JooqTradingReadProjection {
    private final DSLContext dsl;

    public JooqTradingReadProjection(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    public CashReservationSummary cashReservationSummary(ExecutionScope scope) {
        scope = requiredScope(scope);
        return dsl.fetchOne("""
                select coalesce(sum(r.reserved), 0) as reserved,
                       coalesce(sum(r.consumed), 0) as consumed,
                       coalesce(sum(r.released), 0) as released,
                       coalesce(sum(r.reserved - r.consumed - r.released), 0) as active
                from trading.execution_order_scope s
                join trading.execution_resource_reservation r on r.order_id = s.order_id
                where s.bot_id = ? and s.partition_id = ? and s.flow_id = ?
                  and r.resource_type = 'CASH_BUYING_POWER'
                """, scope.botId(), scope.partitionId(), scope.flowId()).map(record -> new CashReservationSummary(
                decimal(record, "reserved"), decimal(record, "consumed"),
                decimal(record, "released"), decimal(record, "active")));
    }

    public List<ReservationView> reservations(ExecutionScope scope) {
        scope = requiredScope(scope);
        return dsl.fetch("""
                select r.reservation_id, r.order_id, r.resource_type, r.resource_key,
                       r.reserved, r.consumed, r.released, r.status, r.version,
                       r.created_at, r.updated_at, r.terminal_reason
                from trading.execution_order_scope s
                join trading.execution_resource_reservation r on r.order_id = s.order_id
                where s.bot_id = ? and s.partition_id = ? and s.flow_id = ?
                order by r.created_at, r.reservation_id
                """, scope.botId(), scope.partitionId(), scope.flowId()).map(record -> new ReservationView(
                record.get("reservation_id", UUID.class), record.get("order_id", UUID.class),
                record.get("resource_type", String.class), record.get("resource_key", String.class),
                decimal(record, "reserved"), decimal(record, "consumed"), decimal(record, "released"),
                record.get("status", String.class), record.get("version", Long.class),
                instant(record, "created_at"), instant(record, "updated_at"),
                record.get("terminal_reason", String.class)));
    }

    public List<PositionView> positions(ExecutionScope scope) {
        scope = requiredScope(scope);
        return dsl.fetch("""
                select instrument_id, quantity, cost_basis, realized_pnl, version, updated_at
                from trading.execution_flow_position_projection
                where bot_id = ? and partition_id = ? and flow_id = ?
                order by instrument_id
                """, scope.botId(), scope.partitionId(), scope.flowId()).map(record -> new PositionView(
                record.get("instrument_id", UUID.class), decimal(record, "quantity"),
                decimal(record, "cost_basis"), decimal(record, "realized_pnl"),
                record.get("version", Long.class), instant(record, "updated_at")));
    }

    public List<OrderView> orders(ExecutionScope scope) {
        scope = requiredScope(scope);
        return dsl.fetch("""
                select o.order_id, o.intent_id, o.candidate_id, o.instrument_id, o.side,
                       o.quantity, o.order_type, o.time_in_force, o.status,
                       o.cumulative_filled_quantity, o.version, o.created_at,
                       o.last_transition_at, o.terminal_reason
                from trading.execution_order_scope s
                join trading.trading_order o on o.order_id = s.order_id
                where s.bot_id = ? and s.partition_id = ? and s.flow_id = ?
                order by o.created_at, o.order_id
                """, scope.botId(), scope.partitionId(), scope.flowId()).map(record -> new OrderView(
                record.get("order_id", UUID.class), record.get("intent_id", UUID.class),
                record.get("candidate_id", UUID.class), record.get("instrument_id", UUID.class),
                record.get("side", String.class), decimal(record, "quantity"),
                record.get("order_type", String.class), record.get("time_in_force", String.class),
                record.get("status", String.class), decimal(record, "cumulative_filled_quantity"),
                record.get("version", Long.class), instant(record, "created_at"),
                instant(record, "last_transition_at"), record.get("terminal_reason", String.class)));
    }

    public List<FillView> fills(ExecutionScope scope) {
        scope = requiredScope(scope);
        return dsl.fetch("""
                select f.fill_record_id, f.root_fill_id, f.correction_of_record_id,
                       f.order_id, f.source_execution_id, f.revision, f.kind,
                       f.quantity, f.price, f.commission, f.slippage,
                       f.occurred_at, f.received_at
                from trading.execution_order_scope s
                join trading.execution_fill_record f on f.order_id = s.order_id
                where s.bot_id = ? and s.partition_id = ? and s.flow_id = ?
                order by f.occurred_at, f.source_execution_id, f.revision
                """, scope.botId(), scope.partitionId(), scope.flowId()).map(record -> new FillView(
                record.get("fill_record_id", UUID.class), record.get("root_fill_id", UUID.class),
                record.get("correction_of_record_id", UUID.class), record.get("order_id", UUID.class),
                record.get("source_execution_id", String.class), record.get("revision", Integer.class),
                record.get("kind", String.class), decimal(record, "quantity"), decimal(record, "price"),
                decimal(record, "commission"), decimal(record, "slippage"),
                instant(record, "occurred_at"), instant(record, "received_at")));
    }

    public List<LedgerEntryView> ledger(ExecutionScope scope) {
        scope = requiredScope(scope);
        return dsl.fetch("""
                select t.transaction_id, t.source_event_id, t.posted_at, t.posting_kind,
                       t.reverses_transaction_id, t.corrects_transaction_id, s.order_id,
                       e.entry_id, e.entry_sequence, e.account_code, e.direction,
                       e.currency, e.amount
                from trading.execution_ledger_scope s
                join trading.official_ledger_transaction t on t.transaction_id = s.transaction_id
                join trading.official_ledger_entry e on e.transaction_id = t.transaction_id
                where s.bot_id = ? and s.partition_id = ? and s.flow_id = ?
                order by t.posted_at, t.transaction_id, e.entry_sequence
                """, scope.botId(), scope.partitionId(), scope.flowId()).map(record -> new LedgerEntryView(
                record.get("transaction_id", UUID.class), record.get("source_event_id", UUID.class),
                record.get("order_id", UUID.class), instant(record, "posted_at"),
                record.get("posting_kind", String.class), record.get("reverses_transaction_id", UUID.class),
                record.get("corrects_transaction_id", UUID.class), record.get("entry_id", UUID.class),
                record.get("entry_sequence", Integer.class), record.get("account_code", String.class),
                record.get("direction", String.class), record.get("currency", String.class).trim(),
                decimal(record, "amount")));
    }

    public List<TradingReasonView> reasons(ExecutionScope scope) {
        scope = requiredScope(scope);
        return dsl.fetch("""
                select o.order_id as reason_id, o.order_id, 'EXECUTION_SCOPE' as scope_level,
                       'REJECTION' as reason_type,
                       o.terminal_reason as reason_code, o.terminal_reason as detail,
                       o.last_transition_at as occurred_at
                from trading.execution_order_scope s
                join trading.trading_order o on o.order_id = s.order_id
                where s.bot_id = ? and s.partition_id = ? and s.flow_id = ?
                  and o.status = 'REJECTED'
                union all
                select r.reason_id, r.order_id, 'EXECUTION_SCOPE' as scope_level,
                       r.reason_type, r.reason_code,
                       r.detail, r.occurred_at
                from trading.execution_projection_reason r
                where r.bot_id = ? and r.partition_id = ? and r.flow_id = ?
                union all
                select s.settlement_id as reason_id, null::uuid as order_id, 'BOT' as scope_level,
                       'SETTLEMENT' as reason_type, s.stop_reason as reason_code,
                       case when s.terminal_reason is null then s.reason_detail
                            else s.reason_detail || ': ' || s.terminal_reason end as detail,
                       s.requested_at as occurred_at
                from trading.bot_stop_settlement s
                where s.bot_id = ?
                order by occurred_at, reason_type, reason_id
                """, scope.botId(), scope.partitionId(), scope.flowId(),
                scope.botId(), scope.partitionId(), scope.flowId(), scope.botId()).map(record -> new TradingReasonView(
                record.get("reason_id", UUID.class), record.get("order_id", UUID.class),
                ReasonScopeLevel.valueOf(record.get("scope_level", String.class)),
                ProjectionReasonType.valueOf(record.get("reason_type", String.class)),
                record.get("reason_code", String.class), record.get("detail", String.class),
                instant(record, "occurred_at")));
    }

    private static ExecutionScope requiredScope(ExecutionScope scope) {
        return Objects.requireNonNull(scope, "scope");
    }

    private static BigDecimal decimal(Record record, String field) {
        return record.get(field, BigDecimal.class);
    }

    private static Instant instant(Record record, String field) {
        return record.get(field, OffsetDateTime.class).toInstant();
    }

    public record CashReservationSummary(
            BigDecimal reserved, BigDecimal consumed, BigDecimal released, BigDecimal active) {}

    public record ReservationView(
            UUID reservationId, UUID orderId, String resourceType, String resourceKey,
            BigDecimal reserved, BigDecimal consumed, BigDecimal released, String status,
            long version, Instant createdAt, Instant updatedAt, String terminalReason) {}

    public record PositionView(
            UUID instrumentId, BigDecimal quantity, BigDecimal costBasis,
            BigDecimal realizedPnl, long version, Instant updatedAt) {}

    public record OrderView(
            UUID orderId, UUID intentId, UUID candidateId, UUID instrumentId, String side,
            BigDecimal quantity, String orderType, String timeInForce, String status,
            BigDecimal cumulativeFilledQuantity, long version, Instant createdAt,
            Instant lastTransitionAt, String terminalReason) {}

    public record FillView(
            UUID fillRecordId, UUID rootFillId, UUID correctionOfRecordId, UUID orderId,
            String sourceExecutionId, int revision, String kind, BigDecimal quantity,
            BigDecimal price, BigDecimal commission, BigDecimal slippage,
            Instant occurredAt, Instant receivedAt) {}

    public record LedgerEntryView(
            UUID transactionId, UUID sourceEventId, UUID orderId, Instant postedAt,
            String postingKind, UUID reversesTransactionId, UUID correctsTransactionId,
            UUID entryId, int entrySequence, String accountCode, String direction,
            String currency, BigDecimal amount) {}
}
