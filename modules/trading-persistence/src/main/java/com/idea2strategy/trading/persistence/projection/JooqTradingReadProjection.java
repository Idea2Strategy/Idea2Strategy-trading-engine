package com.idea2strategy.trading.persistence.projection;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

/**
 * jOOQ read side for budgets, positions, orders, individual fills, the official ledger and the
 * rejection, reduction and settlement reasons of one owner's bot.
 *
 * <p>This is a query only boundary. It never writes, never owns DDL and never re-records evidence
 * that the canonical append only tables already hold. Every statement lives in
 * {@link CanonicalTradingReadSql} so the executable canonical contract fixture can prove it.
 */
@Repository
public class JooqTradingReadProjection {

    private final DSLContext dsl;

    public JooqTradingReadProjection(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    public Optional<BotBudgetView> botBudget(TradingScope scope) {
        Objects.requireNonNull(scope, "scope");
        return Optional.ofNullable(dsl.fetchOne(
                        CanonicalTradingReadSql.BOT_BUDGET,
                        scope.ownerAccountId(),
                        scope.botId())
                .map(record -> new BotBudgetView(
                        uuid(record, "bot_id"),
                        text(record, "currency_code"),
                        decimal(record, "available_cash_amount"),
                        decimal(record, "active_reservation_amount"),
                        decimal(record, "invested_amount"),
                        decimal(record, "segregated_short_proceeds_amount"),
                        decimal(record, "short_collateral_amount"),
                        instant(record, "valuation_at"),
                        text(record, "valuation_status"),
                        number(record, "last_event_sequence"),
                        instant(record, "updated_at"))));
    }

    public List<PartitionBudgetView> partitionBudgets(TradingScope scope, ProjectionPage page) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(page, "page");
        return dsl.fetch(
                        CanonicalTradingReadSql.PARTITION_BUDGETS,
                        scope.ownerAccountId(),
                        scope.botId(),
                        page.limit(),
                        page.offset())
                .map(record -> new PartitionBudgetView(
                        uuid(record, "bot_id"),
                        uuid(record, "partition_id"),
                        text(record, "currency_code"),
                        decimal(record, "budget_cap_amount"),
                        decimal(record, "active_reservation_amount"),
                        decimal(record, "invested_amount"),
                        decimal(record, "segregated_short_proceeds_amount"),
                        decimal(record, "short_collateral_amount"),
                        instant(record, "valuation_at"),
                        text(record, "valuation_status"),
                        number(record, "last_event_sequence"),
                        instant(record, "updated_at")));
    }

    public List<ReservationView> flowReservations(TradingScope scope, ProjectionPage page) {
        return fetchFlowScoped(
                CanonicalTradingReadSql.FLOW_RESERVATIONS,
                scope,
                page,
                record -> new ReservationView(
                        uuid(record, "reservation_id"),
                        text(record, "reservation_key"),
                        uuid(record, "intent_id"),
                        text(record, "resource_type"),
                        text(record, "status"),
                        text(record, "currency_code"),
                        uuid(record, "instrument_id"),
                        decimal(record, "reserved_amount"),
                        decimal(record, "consumed_amount"),
                        decimal(record, "released_amount"),
                        decimal(record, "reserved_quantity"),
                        decimal(record, "consumed_quantity"),
                        decimal(record, "released_quantity"),
                        instant(record, "created_at"),
                        number(record, "last_event_sequence")));
    }

    public List<FlowPositionView> flowPositions(TradingScope scope, ProjectionPage page) {
        return fetchFlowScoped(
                CanonicalTradingReadSql.FLOW_POSITIONS,
                scope,
                page,
                record -> new FlowPositionView(
                        uuid(record, "instrument_id"),
                        decimal(record, "long_quantity"),
                        decimal(record, "short_quantity"),
                        decimal(record, "cost_basis_amount"),
                        number(record, "last_event_sequence"),
                        text(record, "projection_hash"),
                        instant(record, "updated_at")));
    }

    public List<PartitionPositionView> partitionPositions(TradingScope scope, ProjectionPage page) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(page, "page");
        return dsl.fetch(
                        CanonicalTradingReadSql.PARTITION_POSITIONS,
                        scope.ownerAccountId(),
                        scope.botId(),
                        scope.requirePartitionId(),
                        page.limit(),
                        page.offset())
                .map(record -> new PartitionPositionView(
                        uuid(record, "instrument_id"),
                        decimal(record, "net_quantity"),
                        decimal(record, "average_cost"),
                        decimal(record, "realized_pnl"),
                        decimal(record, "last_valuation_price"),
                        instant(record, "last_valuation_at"),
                        text(record, "valuation_status"),
                        number(record, "last_bot_event_sequence"),
                        instant(record, "updated_at")));
    }

    public List<OrderView> flowOrders(TradingScope scope, ProjectionPage page) {
        return fetchFlowScoped(
                CanonicalTradingReadSql.FLOW_ORDERS,
                scope,
                page,
                record -> new OrderView(
                        uuid(record, "order_id"),
                        uuid(record, "instrument_id"),
                        text(record, "order_key"),
                        text(record, "side"),
                        text(record, "order_type"),
                        text(record, "time_in_force"),
                        decimal(record, "requested_quantity"),
                        decimal(record, "limit_price"),
                        decimal(record, "stop_price"),
                        instant(record, "accepted_at"),
                        instant(record, "expires_at"),
                        text(record, "status"),
                        decimal(record, "filled_quantity"),
                        decimal(record, "remaining_quantity"),
                        decimal(record, "reserved_cash"),
                        decimal(record, "reserved_quantity"),
                        number(record, "last_order_event_sequence"),
                        instant(record, "updated_at")));
    }

    public List<FillView> flowFills(TradingScope scope, ProjectionPage page) {
        return fetchFlowScoped(
                CanonicalTradingReadSql.FLOW_FILLS,
                scope,
                page,
                record -> new FillView(
                        uuid(record, "fill_id"),
                        uuid(record, "order_id"),
                        text(record, "provider_fill_key"),
                        decimal(record, "quantity"),
                        decimal(record, "reference_price"),
                        instant(record, "reference_observed_at"),
                        integer(record, "slippage_rate_bps"),
                        decimal(record, "slippage_amount"),
                        decimal(record, "fill_price"),
                        decimal(record, "gross_amount"),
                        integer(record, "fee_rate_bps"),
                        decimal(record, "fee_amount"),
                        decimal(record, "settlement_cash_delta"),
                        instant(record, "occurred_at"),
                        uuid(record, "allocation_id"),
                        integer(record, "allocation_sequence"),
                        decimal(record, "allocated_quantity"),
                        decimal(record, "allocated_gross_amount"),
                        decimal(record, "allocated_fee_amount"),
                        decimal(record, "allocated_settlement_cash_delta")));
    }

    public List<LedgerEntryView> flowLedgerEntries(TradingScope scope, ProjectionPage page) {
        return fetchFlowScoped(
                CanonicalTradingReadSql.FLOW_LEDGER_ENTRIES,
                scope,
                page,
                record -> new LedgerEntryView(
                        uuid(record, "transaction_id"),
                        text(record, "transaction_type"),
                        text(record, "transaction_key"),
                        text(record, "source_type"),
                        uuid(record, "source_id"),
                        text(record, "currency_code"),
                        uuid(record, "reversal_of_transaction_id"),
                        instant(record, "occurred_at"),
                        text(record, "description_code"),
                        uuid(record, "entry_id"),
                        integer(record, "entry_sequence"),
                        text(record, "direction"),
                        decimal(record, "amount"),
                        decimal(record, "quantity"),
                        text(record, "account_key"),
                        text(record, "account_type")));
    }

    public List<TradingReasonView> flowReasons(TradingScope scope, ProjectionPage page) {
        return fetchFlowScoped(
                CanonicalTradingReadSql.FLOW_REASONS,
                scope,
                page,
                record -> new TradingReasonView(
                        uuid(record, "reason_id"),
                        uuid(record, "order_id"),
                        ReasonScopeLevel.valueOf(text(record, "scope_level")),
                        ProjectionReasonType.valueOf(text(record, "reason_type")),
                        text(record, "reason_code"),
                        text(record, "detail"),
                        instant(record, "occurred_at")));
    }

    private <T> List<T> fetchFlowScoped(
            String sql,
            TradingScope scope,
            ProjectionPage page,
            org.jooq.RecordMapper<Record, T> mapper) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(page, "page");
        return dsl.fetch(
                        sql,
                        scope.ownerAccountId(),
                        scope.botId(),
                        scope.requirePartitionId(),
                        scope.requireFlowId(),
                        page.limit(),
                        page.offset())
                .map(mapper);
    }

    private static UUID uuid(Record record, String field) {
        return record.get(field, UUID.class);
    }

    private static String text(Record record, String field) {
        String value = record.get(field, String.class);
        return value == null ? null : value.trim();
    }

    private static BigDecimal decimal(Record record, String field) {
        return record.get(field, BigDecimal.class);
    }

    private static Integer integer(Record record, String field) {
        return record.get(field, Integer.class);
    }

    private static Long number(Record record, String field) {
        return record.get(field, Long.class);
    }

    private static Instant instant(Record record, String field) {
        OffsetDateTime value = record.get(field, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    public record BotBudgetView(
            UUID botId, String currencyCode, BigDecimal availableCashAmount,
            BigDecimal activeReservationAmount, BigDecimal investedAmount,
            BigDecimal segregatedShortProceedsAmount, BigDecimal shortCollateralAmount,
            Instant valuationAt, String valuationStatus, Long lastEventSequence,
            Instant updatedAt) {}

    public record PartitionBudgetView(
            UUID botId, UUID partitionId, String currencyCode, BigDecimal budgetCapAmount,
            BigDecimal activeReservationAmount, BigDecimal investedAmount,
            BigDecimal segregatedShortProceedsAmount, BigDecimal shortCollateralAmount,
            Instant valuationAt, String valuationStatus, Long lastEventSequence,
            Instant updatedAt) {}

    public record ReservationView(
            UUID reservationId, String reservationKey, UUID intentId, String resourceType,
            String status, String currencyCode, UUID instrumentId, BigDecimal reservedAmount,
            BigDecimal consumedAmount, BigDecimal releasedAmount, BigDecimal reservedQuantity,
            BigDecimal consumedQuantity, BigDecimal releasedQuantity, Instant createdAt,
            Long lastEventSequence) {}

    public record FlowPositionView(
            UUID instrumentId, BigDecimal longQuantity, BigDecimal shortQuantity,
            BigDecimal costBasisAmount, Long lastEventSequence, String projectionHash,
            Instant updatedAt) {}

    public record PartitionPositionView(
            UUID instrumentId, BigDecimal netQuantity, BigDecimal averageCost,
            BigDecimal realizedPnl, BigDecimal lastValuationPrice, Instant lastValuationAt,
            String valuationStatus, Long lastBotEventSequence, Instant updatedAt) {}

    public record OrderView(
            UUID orderId, UUID instrumentId, String orderKey, String side, String orderType,
            String timeInForce, BigDecimal requestedQuantity, BigDecimal limitPrice,
            BigDecimal stopPrice, Instant acceptedAt, Instant expiresAt, String status,
            BigDecimal filledQuantity, BigDecimal remainingQuantity, BigDecimal reservedCash,
            BigDecimal reservedQuantity, Long lastOrderEventSequence, Instant updatedAt) {}

    public record FillView(
            UUID fillId, UUID orderId, String providerFillKey, BigDecimal quantity,
            BigDecimal referencePrice, Instant referenceObservedAt, Integer slippageRateBps,
            BigDecimal slippageAmount, BigDecimal fillPrice, BigDecimal grossAmount,
            Integer feeRateBps, BigDecimal feeAmount, BigDecimal settlementCashDelta,
            Instant occurredAt, UUID allocationId, Integer allocationSequence,
            BigDecimal allocatedQuantity, BigDecimal allocatedGrossAmount,
            BigDecimal allocatedFeeAmount, BigDecimal allocatedSettlementCashDelta) {}

    public record LedgerEntryView(
            UUID transactionId, String transactionType, String transactionKey, String sourceType,
            UUID sourceId, String currencyCode, UUID reversalOfTransactionId, Instant occurredAt,
            String descriptionCode, UUID entryId, Integer entrySequence, String direction,
            BigDecimal amount, BigDecimal quantity, String accountKey, String accountType) {}
}
