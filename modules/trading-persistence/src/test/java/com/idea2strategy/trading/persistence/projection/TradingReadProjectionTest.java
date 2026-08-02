package com.idea2strategy.trading.persistence.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class TradingReadProjectionTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final Instant NOW = Instant.parse("2026-08-02T14:30:00Z");
    private static DriverManagerDataSource dataSource;
    private static JdbcClient jdbc;
    private static TransactionTemplate transaction;
    private static JooqTradingReadProjection query;
    private static PostgresProjectionAttributionStore attribution;

    @BeforeAll
    static void setUpDatabase() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = JdbcClient.create(dataSource);
        var transactionManager = new JdbcTransactionManager(dataSource);
        transaction = new TransactionTemplate(transactionManager);
        query = new JooqTradingReadProjection(DSL.using(dataSource, SQLDialect.POSTGRES));
        attribution = new PostgresProjectionAttributionStore(jdbc, transactionManager);
    }

    @BeforeEach
    void clearProjectionFacts() {
        jdbc.sql("truncate table trading.execution_projection_reason, "
                        + "trading.execution_ledger_scope, trading.execution_order_scope cascade")
                .update();
        jdbc.sql("truncate table trading.execution_resource_reservation, trading.execution_fill_record, "
                        + "trading.execution_flow_position_projection, trading.official_ledger_transaction, "
                        + "trading.trading_order, trading.bot_stop_settlement cascade")
                .update();
    }

    @Test
    void everyProjectionIsStrictlyIsolatedByBotPartitionAndFlow() {
        ExecutionScope requested = scope();
        ExecutionScope other = siblingScope(requested);
        UUID requestedOrder = insertRejectedOrder("BUDGET_LIMIT");
        UUID otherOrder = insertRejectedOrder("OTHER_SCOPE");
        attribution.attributeOrder(requestedOrder, requested, NOW);
        attribution.attributeOrder(otherOrder, other, NOW);
        insertReservation(requestedOrder, "100", "25", "5");
        insertReservation(otherOrder, "999", "0", "0");
        insertFill(requestedOrder, "fill-requested");
        insertFill(otherOrder, "fill-other");
        insertPosition(requested, "2.5", "250");
        insertPosition(other, "99", "9999");
        attribution.appendReason(new ProjectionReason(
                UUID.randomUUID(), requested, requestedOrder, ProjectionReasonType.RESIZE,
                "RISK_LIMIT", "request reduced to safe amount", NOW));
        attribution.appendReason(new ProjectionReason(
                UUID.randomUUID(), other, otherOrder, ProjectionReasonType.SETTLEMENT,
                "OTHER", "must not leak", NOW));
        insertBotSettlement(requested.botId());

        var budget = query.cashReservationSummary(requested);
        assertDecimal("100", budget.reserved());
        assertDecimal("25", budget.consumed());
        assertDecimal("5", budget.released());
        assertDecimal("70", budget.active());
        assertEquals(1, query.reservations(requested).size());
        assertEquals(requestedOrder, query.reservations(requested).getFirst().orderId());
        assertEquals(1, query.positions(requested).size());
        assertDecimal("2.5", query.positions(requested).getFirst().quantity());
        assertEquals(1, query.orders(requested).size());
        assertEquals(requestedOrder, query.orders(requested).getFirst().orderId());
        assertEquals(1, query.fills(requested).size());
        assertEquals("fill-requested", query.fills(requested).getFirst().sourceExecutionId());
        assertEquals(3, query.reasons(requested).size());
        assertEquals(
                java.util.List.of(
                        ProjectionReasonType.REJECTION,
                        ProjectionReasonType.RESIZE,
                        ProjectionReasonType.SETTLEMENT),
                query.reasons(requested).stream().map(TradingReasonView::type).toList());
        assertEquals(ReasonScopeLevel.BOT, query.reasons(requested).getLast().scopeLevel());
    }

    @Test
    void officialLedgerRowsRequireExplicitScopeAttributionAndPreserveEntryOrder() {
        ExecutionScope requested = scope();
        ExecutionScope other = siblingScope(requested);
        UUID requestedTransaction = insertLedger("10.25");
        UUID otherTransaction = insertLedger("77");
        attribution.attributeLedger(requestedTransaction, requested, null, NOW);
        attribution.attributeLedger(requestedTransaction, requested, null, NOW);
        attribution.attributeLedger(otherTransaction, other, null, NOW);
        org.junit.jupiter.api.Assertions.assertThrows(
                ProjectionAttributionConflictException.class,
                () -> attribution.attributeLedger(requestedTransaction, requested, null, NOW.plusSeconds(1)));

        var rows = query.ledger(requested);
        assertEquals(2, rows.size());
        assertEquals(requestedTransaction, rows.getFirst().transactionId());
        assertEquals(1, rows.getFirst().entrySequence());
        assertEquals(2, rows.getLast().entrySequence());
        assertDecimal("10.25", rows.getFirst().amount());
    }

    @Test
    void repeatedAttributionAndReasonDeliveryIsIdempotentButConflictingOwnershipFailsClosed() {
        ExecutionScope requested = scope();
        UUID order = insertRejectedOrder("BUDGET_LIMIT");
        attribution.attributeOrder(order, requested, NOW);
        attribution.attributeOrder(order, requested, NOW);
        var reason = new ProjectionReason(
                UUID.randomUUID(), requested, order, ProjectionReasonType.SETTLEMENT,
                "BOT_STOPPED", "positions liquidated", NOW);
        attribution.appendReason(reason);
        attribution.appendReason(reason);

        org.junit.jupiter.api.Assertions.assertThrows(
                ProjectionAttributionConflictException.class,
                () -> attribution.attributeOrder(order, scope(), NOW));
        org.junit.jupiter.api.Assertions.assertThrows(
                ProjectionAttributionConflictException.class,
                () -> attribution.attributeOrder(order, requested, NOW.plusSeconds(1)));
        org.junit.jupiter.api.Assertions.assertThrows(
                ProjectionAttributionConflictException.class,
                () -> attribution.appendReason(new ProjectionReason(
                        reason.reasonId(), requested, order, ProjectionReasonType.SETTLEMENT,
                        "DIFFERENT", "positions liquidated", NOW)));
        assertEquals(2, query.reasons(requested).size());
    }

    private static ExecutionScope scope() {
        return new ExecutionScope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    private static ExecutionScope siblingScope(ExecutionScope scope) {
        return new ExecutionScope(scope.botId(), UUID.randomUUID(), UUID.randomUUID());
    }

    private static UUID insertRejectedOrder(String reason) {
        UUID orderId = UUID.randomUUID();
        jdbc.sql("""
                insert into trading.trading_order (
                    order_id, create_command_id, request_fingerprint, intent_id, candidate_id,
                    instrument_id, side, quantity, order_type, time_in_force, status,
                    cumulative_filled_quantity, version, created_at, last_transition_at, terminal_reason
                ) values (:orderId, :commandId, :fingerprint, :intentId, :candidateId,
                    :instrumentId, 'BUY', 1, 'MARKET', 'DAY', 'REJECTED', 0, 1, :at, :at, :reason)
                """)
                .param("orderId", orderId)
                .param("commandId", UUID.randomUUID())
                .param("fingerprint", "a".repeat(64))
                .param("intentId", UUID.randomUUID())
                .param("candidateId", UUID.randomUUID())
                .param("instrumentId", UUID.randomUUID())
                .param("at", OffsetDateTime.parse("2026-08-02T14:30:00Z"))
                .param("reason", reason)
                .update();
        return orderId;
    }

    private static void insertReservation(UUID orderId, String reserved, String consumed, String released) {
        jdbc.sql("""
                insert into trading.execution_resource_reservation (
                    reservation_id, create_command_id, request_fingerprint, order_id, resource_type,
                    resource_key, reserved, consumed, released, status, version, created_at, updated_at)
                values (:id, :command, :fingerprint, :orderId, 'CASH_BUYING_POWER', 'USD',
                    :reserved, :consumed, :released, 'ACTIVE', 1, :at, :at)
                """)
                .param("id", UUID.randomUUID()).param("command", UUID.randomUUID())
                .param("fingerprint", "b".repeat(64)).param("orderId", orderId)
                .param("reserved", new BigDecimal(reserved)).param("consumed", new BigDecimal(consumed))
                .param("released", new BigDecimal(released))
                .param("at", OffsetDateTime.parse("2026-08-02T14:30:00Z")).update();
    }

    private static void insertFill(UUID orderId, String sourceExecutionId) {
        jdbc.sql("""
                insert into trading.execution_fill_record (
                    fill_record_id, request_fingerprint, root_fill_id, order_id, source_execution_id,
                    revision, kind, quantity, price, commission, slippage, occurred_at, received_at)
                values (:id, :fingerprint, :root, :orderId, :source, 0, 'ORIGINAL', 0.5, 100, 0.1, 0.05, :at, :at)
                """)
                .param("id", UUID.randomUUID()).param("fingerprint", "c".repeat(64))
                .param("root", UUID.randomUUID()).param("orderId", orderId).param("source", sourceExecutionId)
                .param("at", OffsetDateTime.parse("2026-08-02T14:30:00Z")).update();
    }

    private static void insertPosition(ExecutionScope scope, String quantity, String costBasis) {
        jdbc.sql("""
                insert into trading.execution_flow_position_projection (
                    bot_id, partition_id, flow_id, instrument_id, quantity, cost_basis,
                    realized_pnl, version, updated_at)
                values (:bot, :partition, :flow, :instrument, :quantity, :basis, 0, 1, :at)
                """)
                .param("bot", scope.botId()).param("partition", scope.partitionId()).param("flow", scope.flowId())
                .param("instrument", UUID.randomUUID()).param("quantity", new BigDecimal(quantity))
                .param("basis", new BigDecimal(costBasis))
                .param("at", OffsetDateTime.parse("2026-08-02T14:30:00Z")).update();
    }

    private static UUID insertLedger(String amount) {
        UUID transactionId = UUID.randomUUID();
        UUID sourceEventId = UUID.randomUUID();
        transaction.executeWithoutResult(status -> {
            jdbc.sql("""
                    insert into trading.official_ledger_transaction
                        (transaction_id, source_event_id, posted_at, posting_kind)
                    values (:transactionId, :sourceEventId, :at, 'STANDARD')
                    """).param("transactionId", transactionId).param("sourceEventId", sourceEventId)
                    .param("at", OffsetDateTime.parse("2026-08-02T14:30:00Z")).update();
            for (int sequence = 1; sequence <= 2; sequence++) {
                jdbc.sql("""
                        insert into trading.official_ledger_entry (
                            entry_id, transaction_id, entry_sequence, account_code, direction,
                            currency, amount, source_event_id)
                        values (:entryId, :transactionId, :sequence, :account, :direction,
                            'USD', :amount, :sourceEventId)
                        """).param("entryId", UUID.randomUUID()).param("transactionId", transactionId)
                        .param("sequence", sequence).param("account", sequence == 1 ? "CASH" : "POSITION")
                        .param("direction", sequence == 1 ? "DEBIT" : "CREDIT")
                        .param("amount", new BigDecimal(amount)).param("sourceEventId", sourceEventId).update();
            }
        });
        return transactionId;
    }

    private static void insertBotSettlement(UUID botId) {
        jdbc.sql("""
                insert into trading.bot_stop_settlement (
                    settlement_id, bot_id, stop_reason, reason_detail, checkpoint,
                    version, requested_at, updated_at)
                values (:settlementId, :botId, 'USER_REQUEST', 'owner requested stop',
                    'REQUESTED', 1, :at, :at)
                """).param("settlementId", UUID.randomUUID()).param("botId", botId)
                .param("at", OffsetDateTime.parse("2026-08-02T14:30:00Z")).update();
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
