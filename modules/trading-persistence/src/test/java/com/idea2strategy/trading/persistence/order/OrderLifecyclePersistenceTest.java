package com.idea2strategy.trading.persistence.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idea2strategy.trading.application.order.CancelOrderCommand;
import com.idea2strategy.trading.application.order.ExpireOrderCommand;
import com.idea2strategy.trading.application.order.FillOrderCommand;
import com.idea2strategy.trading.application.order.OrderLifecycleConflictException;
import com.idea2strategy.trading.application.order.OrderLifecycleVersionConflictException;
import com.idea2strategy.trading.domain.order.OrderLifecycle;
import com.idea2strategy.trading.domain.order.OrderLifecycleFactory;
import com.idea2strategy.trading.domain.order.OrderSide;
import com.idea2strategy.trading.domain.order.OrderStatus;
import com.idea2strategy.trading.domain.order.OrderTerms;
import com.idea2strategy.trading.domain.order.OrderType;
import com.idea2strategy.trading.domain.order.TimeInForce;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class OrderLifecyclePersistenceTest {
    private static final UUID INTENT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID CANDIDATE_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID INSTRUMENT_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final Instant CREATED_AT = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-08-01T00:01:00Z");
    private static final Instant T2 = Instant.parse("2026-08-01T00:02:00Z");
    private static final Instant T3 = Instant.parse("2026-08-01T00:03:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    private static DriverManagerDataSource dataSource;
    private static JdbcClient jdbcClient;
    private static JdbcTransactionManager transactionManager;
    private static PostgresOrderLifecycleStore store;
    private static JooqOrderLifecycleQuery query;

    @BeforeAll
    static void migrateDatabase() {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbcClient = JdbcClient.create(dataSource);
        transactionManager = new JdbcTransactionManager(dataSource);
        store = newStore();
        query = new JooqOrderLifecycleQuery(DSL.using(dataSource, SQLDialect.POSTGRES));
    }

    @BeforeEach
    void clearLifecycleRows() {
        jdbcClient.sql("truncate table trading.trading_order cascade").update();
    }

    @Test
    void exactCreationRetrySurvivesStoreRestartWithOneAtomicLifecycle() {
        OrderLifecycle desired = acceptedOrder();

        OrderLifecycle first = store.createOrLoad(desired);
        OrderLifecycle repeated = store.createOrLoad(desired);
        OrderLifecycle restarted = newStore().createOrLoad(desired);

        assertEquals(desired, first);
        assertEquals(desired, repeated);
        assertEquals(desired, restarted);
        assertEquals(desired, query.findByOrderId(desired.orderId()).orElseThrow().toDomain());
        assertEquals(1, count("trading.trading_order"));
        assertEquals(1, query.findTransitions(desired.orderId()).size());
        assertEquals(1, count("trading.order_lifecycle_command"));
    }

    @Test
    void exactCreationRetryAfterTransitionReturnsLatestSnapshotAcrossRestart() {
        OrderLifecycle desired = store.createOrLoad(acceptedOrder(2, TimeInForce.DAY, null));
        OrderLifecycle advanced = store.apply(fill(21, desired, 1, "2", T1));

        OrderLifecycle replayed = store.createOrLoad(desired);
        OrderLifecycle restartedReplay = newStore().createOrLoad(desired);

        assertEquals(advanced, replayed);
        assertEquals(advanced, restartedReplay);
        assertEquals(List.of(1L, 2L), versions(desired.orderId()));
        assertEquals(2, receiptCount(desired.orderId()));
    }

    @Test
    void concurrentIdenticalCreationConvergesOnOneAtomicLifecycle() throws Exception {
        OrderLifecycle desired = acceptedOrder(3, TimeInForce.DAY, null);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<OrderLifecycle> create = () -> {
            ready.countDown();
            start.await();
            return newStore().createOrLoad(desired);
        };

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(create);
            var second = executor.submit(create);
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            assertEquals(desired, first.get());
            assertEquals(desired, second.get());
        }
        assertEquals(desired, snapshot(desired.orderId()));
        assertEquals(1, count("trading.trading_order"));
        assertEquals(1, count("trading.order_lifecycle_transition"));
        assertEquals(1, count("trading.order_lifecycle_command"));
    }

    @Test
    void creationReceiptConflictRollsBackNewSnapshot() {
        OrderLifecycle desired = acceptedOrder(4, TimeInForce.DAY, null);
        jdbcClient.sql("""
                        create function trading.reject_selected_lifecycle_command()
                        returns trigger language plpgsql as $$
                        begin
                            if new.command_id = '%s'::uuid then
                                return null;
                            end if;
                            return new;
                        end
                        $$
                        """.formatted(desired.createCommandId())).update();
        jdbcClient.sql("""
                        create trigger reject_selected_lifecycle_command
                        before insert on trading.order_lifecycle_command
                        for each row execute function trading.reject_selected_lifecycle_command()
                        """).update();
        try {
            assertThrows(OrderLifecycleConflictException.class, () -> store.createOrLoad(desired));
            assertFalse(query.findByOrderId(desired.orderId()).isPresent());
            assertTrue(query.findTransitions(desired.orderId()).isEmpty());
            assertEquals(0, jdbcClient.sql("select count(*) from trading.order_lifecycle_command where order_id = :orderId")
                    .param("orderId", desired.orderId())
                    .query(Integer.class)
                    .single());
        } finally {
            jdbcClient.sql("drop trigger reject_selected_lifecycle_command on trading.order_lifecycle_command").update();
            jdbcClient.sql("drop function trading.reject_selected_lifecycle_command()").update();
        }
    }

    @Test
    void creationRejectsValuesPostgresWouldRoundBeforeWritingAnything() {
        OrderLifecycle tooPreciseQuantity = new OrderLifecycleFactory().accepted(new OrderTerms(
                id("10000000", 5),
                id("20000000", 5),
                INSTRUMENT_ID,
                OrderSide.BUY,
                new BigDecimal("1.0000000000000000001"),
                OrderType.MARKET,
                TimeInForce.DAY,
                null,
                null,
                null,
                null), CREATED_AT);
        OrderLifecycle tooPreciseTimestamp = new OrderLifecycleFactory().accepted(new OrderTerms(
                id("10000000", 6),
                id("20000000", 6),
                INSTRUMENT_ID,
                OrderSide.BUY,
                BigDecimal.ONE,
                OrderType.MARKET,
                TimeInForce.DAY,
                null,
                null,
                null,
                null), CREATED_AT.plusNanos(1));

        assertThrows(IllegalArgumentException.class, () -> store.createOrLoad(tooPreciseQuantity));
        assertThrows(IllegalArgumentException.class, () -> store.createOrLoad(tooPreciseTimestamp));
        assertEquals(0, count("trading.trading_order"));
        assertEquals(0, count("trading.order_lifecycle_transition"));
        assertEquals(0, count("trading.order_lifecycle_command"));
    }

    @Test
    void creationRejectsAdvancedAggregateBeforeWritingAnything() {
        OrderLifecycle initial = acceptedOrder(7, TimeInForce.DAY, null);
        OrderLifecycle advanced = initial.applyFill(new BigDecimal("2"), T1);

        assertThrows(IllegalArgumentException.class, () -> store.createOrLoad(advanced));

        assertEquals(0, count("trading.trading_order"));
        assertEquals(0, count("trading.order_lifecycle_transition"));
        assertEquals(0, count("trading.order_lifecycle_command"));
    }

    @Test
    void changedCreationContentOrReusedCandidateConflictsWithoutPartialWrites() {
        OrderLifecycle desired = acceptedOrder(1, TimeInForce.DAY, null);
        store.createOrLoad(desired);
        OrderLifecycle changedIntentContent = acceptedOrder(
                desired.terms().intentId(),
                desired.terms().candidateId(),
                UUID.fromString("30000000-0000-0000-0000-000000000099"),
                TimeInForce.DAY,
                null);
        OrderLifecycle reusedCandidate = acceptedOrder(
                id("10000000", 2),
                desired.terms().candidateId(),
                INSTRUMENT_ID,
                TimeInForce.DAY,
                null);

        assertThrows(OrderLifecycleConflictException.class, () -> store.createOrLoad(changedIntentContent));
        assertThrows(OrderLifecycleConflictException.class, () -> store.createOrLoad(reusedCandidate));

        assertEquals(desired, snapshot(desired.orderId()));
        assertEquals(1, count("trading.trading_order"));
        assertEquals(1, count("trading.order_lifecycle_transition"));
        assertEquals(1, count("trading.order_lifecycle_command"));
    }

    @Test
    void acceptedOrdersSupportPartialFillFullFillAndCancellation() {
        OrderLifecycle partialSource = create(acceptedOrder(11, TimeInForce.DAY, null));
        OrderLifecycle fullSource = create(acceptedOrder(12, TimeInForce.DAY, null));
        OrderLifecycle cancelSource = create(acceptedOrder(13, TimeInForce.DAY, null));

        OrderLifecycle partial = store.apply(fill(101, partialSource, 1, "2", T1));
        OrderLifecycle filled = store.apply(fill(102, fullSource, 1, "10", T1));
        OrderLifecycle cancelled = store.apply(cancel(103, cancelSource, 1, "USER_CANCEL", T1));

        assertEquals(OrderStatus.PARTIALLY_FILLED, partial.status());
        assertEquals(new BigDecimal("2"), partial.cumulativeFilledQuantity());
        assertEquals(OrderStatus.FILLED, filled.status());
        assertEquals(0, filled.cumulativeFilledQuantity().compareTo(new BigDecimal("10")));
        assertEquals(OrderStatus.CANCELLED, cancelled.status());
        assertEquals("USER_CANCEL", cancelled.terminalReason());
        assertEquals(List.of(1L, 2L), versions(partial.orderId()));
        assertEquals(List.of(1L, 2L), versions(filled.orderId()));
        assertEquals(List.of(1L, 2L), versions(cancelled.orderId()));
    }

    @Test
    void partiallyFilledOrdersSupportAnotherPartialFillFullFillCancellationAndExpiration() {
        OrderLifecycle partialAgainSource = partial(create(acceptedOrder(21, TimeInForce.DAY, null)), 201);
        OrderLifecycle fullSource = partial(create(acceptedOrder(22, TimeInForce.DAY, null)), 202);
        OrderLifecycle cancelSource = partial(create(acceptedOrder(23, TimeInForce.DAY, null)), 203);
        OrderLifecycle expireSource = partial(create(acceptedOrder(24, TimeInForce.DAY, null)), 204);

        OrderLifecycle partialAgain = store.apply(fill(211, partialAgainSource, 2, "3", T2));
        OrderLifecycle filled = store.apply(fill(212, fullSource, 2, "8", T2));
        OrderLifecycle cancelled = store.apply(cancel(213, cancelSource, 2, "RISK_CANCEL", T2));
        OrderLifecycle expired = store.apply(expire(214, expireSource, 2, T2, T1));

        assertEquals(OrderStatus.PARTIALLY_FILLED, partialAgain.status());
        assertEquals(new BigDecimal("5"), partialAgain.cumulativeFilledQuantity());
        assertEquals(OrderStatus.FILLED, filled.status());
        assertEquals(OrderStatus.CANCELLED, cancelled.status());
        assertEquals(new BigDecimal("2"), cancelled.cumulativeFilledQuantity());
        assertEquals(OrderStatus.EXPIRED, expired.status());
        assertEquals("DAY_SESSION_CLOSE", expired.terminalReason());
        assertEquals(List.of(1L, 2L, 3L), versions(expired.orderId()));
    }

    @Test
    void exactCommandReplayIsCheckedBeforeVersionAndReturnsLatestSnapshot() {
        OrderLifecycle desired = create(acceptedOrder(31, TimeInForce.DAY, null));
        FillOrderCommand firstCommand = fill(301, desired, 1, "2", T1);

        OrderLifecycle first = store.apply(firstCommand);
        OrderLifecycle later = store.apply(fill(302, first, 2, "1", T2));
        OrderLifecycle replayed = store.apply(firstCommand);
        OrderLifecycle restartedReplay = newStore().apply(firstCommand);

        assertEquals(OrderStatus.PARTIALLY_FILLED, first.status());
        assertEquals(later, replayed);
        assertEquals(later, restartedReplay);
        assertEquals(List.of(1L, 2L, 3L), versions(desired.orderId()));
        assertEquals(3, receiptCount(desired.orderId()));
    }

    @Test
    void reusedCommandIdWithChangedContentConflictsBeforeStaleVersionHandling() {
        OrderLifecycle desired = create(acceptedOrder(32, TimeInForce.DAY, null));
        UUID commandId = commandId(321);
        store.apply(new FillOrderCommand(commandId, desired.orderId(), 1, new BigDecimal("2"), T1));

        assertThrows(OrderLifecycleConflictException.class, () -> store.apply(
                new FillOrderCommand(commandId, desired.orderId(), 1, new BigDecimal("3"), T1)));

        assertEquals(2L, snapshot(desired.orderId()).version());
        assertEquals(List.of(1L, 2L), versions(desired.orderId()));
        assertEquals(2, receiptCount(desired.orderId()));
    }

    @Test
    void distinctStaleCommandFailsWithVersionConflictAndNoWrites() {
        OrderLifecycle desired = create(acceptedOrder(33, TimeInForce.DAY, null));
        store.apply(fill(331, desired, 1, "2", T1));

        assertThrows(OrderLifecycleVersionConflictException.class, () -> store.apply(fill(332, desired, 1, "1", T2)));

        assertEquals(2L, snapshot(desired.orderId()).version());
        assertEquals(List.of(1L, 2L), versions(desired.orderId()));
        assertEquals(2, receiptCount(desired.orderId()));
    }

    @Test
    void competingSameVersionCommandsProduceOneWinner() throws Exception {
        OrderLifecycle desired = create(acceptedOrder(34, TimeInForce.DAY, null));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<OrderLifecycle> firstApply = concurrentApply(fill(341, desired, 1, "2", T1), ready, start);
        Callable<OrderLifecycle> secondApply = concurrentApply(fill(342, desired, 1, "3", T1), ready, start);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(firstApply);
            var second = executor.submit(secondApply);
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            int successes = 0;
            int versionConflicts = 0;
            for (var future : List.of(first, second)) {
                try {
                    assertEquals(2L, future.get().version());
                    successes++;
                } catch (ExecutionException exception) {
                    assertInstanceOf(OrderLifecycleVersionConflictException.class, exception.getCause());
                    versionConflicts++;
                }
            }
            assertEquals(1, successes);
            assertEquals(1, versionConflicts);
        }
        assertEquals(2L, snapshot(desired.orderId()).version());
        assertEquals(2, query.findTransitions(desired.orderId()).size());
        assertEquals(2, receiptCount(desired.orderId()));
    }

    @Test
    void overfillAndOutOfOrderTimeRollbackEveryPersistenceWrite() {
        OrderLifecycle desired = create(acceptedOrder(41, TimeInForce.DAY, null));
        OrderLifecycle partial = store.apply(fill(401, desired, 1, "2", T2));

        assertThrows(IllegalArgumentException.class, () -> store.apply(fill(402, partial, 2, "9", T3)));
        assertThrows(IllegalArgumentException.class, () -> store.apply(fill(403, partial, 2, "1", T1)));

        assertEquals(partial, snapshot(desired.orderId()));
        assertEquals(List.of(1L, 2L), versions(desired.orderId()));
        assertEquals(2, receiptCount(desired.orderId()));
    }

    @Test
    void mutationRejectsValuesPostgresWouldRoundWithoutReceiptsOrHistory() {
        OrderLifecycle desired = create(acceptedOrder(40, TimeInForce.DAY, null));

        assertThrows(IllegalArgumentException.class, () -> store.apply(
                new FillOrderCommand(commandId(404), desired.orderId(), 1,
                        new BigDecimal("0.0000000000000000001"), T1)));
        assertThrows(IllegalArgumentException.class, () -> store.apply(
                new FillOrderCommand(commandId(405), desired.orderId(), 1,
                        BigDecimal.ONE, T1.plusNanos(1))));

        assertEquals(desired, snapshot(desired.orderId()));
        assertEquals(List.of(1L), versions(desired.orderId()));
        assertEquals(1, receiptCount(desired.orderId()));
    }

    @Test
    void allTerminalStatesRejectFurtherTransitionsWithoutWrites() {
        OrderLifecycle filled = store.apply(fill(411, create(acceptedOrder(42, TimeInForce.DAY, null)), 1, "10", T1));
        OrderLifecycle cancelled = store.apply(cancel(412, create(acceptedOrder(43, TimeInForce.DAY, null)), 1, "CANCEL", T1));
        OrderLifecycle expired = store.apply(expire(413, create(acceptedOrder(44, TimeInForce.DAY, null)), 1, T1, T1));
        OrderLifecycle rejected = create(rejectedOrder(45));

        assertThrows(IllegalStateException.class, () -> store.apply(cancel(421, filled, 2, "AGAIN", T2)));
        assertThrows(IllegalStateException.class, () -> store.apply(fill(422, cancelled, 2, "1", T2)));
        assertThrows(IllegalStateException.class, () -> store.apply(fill(423, expired, 2, "1", T2)));
        assertThrows(IllegalStateException.class, () -> store.apply(cancel(424, rejected, 1, "AGAIN", T2)));

        for (OrderLifecycle terminal : List.of(filled, cancelled, expired, rejected)) {
            assertEquals(terminal, snapshot(terminal.orderId()));
            assertEquals(terminal.version(), query.findTransitions(terminal.orderId()).size());
            assertEquals(terminal.version(), receiptCount(terminal.orderId()));
        }
    }

    @Test
    void dayAndGtdExpireAtDeadlineWhileIneligibleDayAndGtcDoNotChange() {
        OrderLifecycle day = create(acceptedOrder(51, TimeInForce.DAY, null));
        OrderLifecycle gtd = create(acceptedOrder(52, TimeInForce.GTD, T2));
        OrderLifecycle gtc = create(acceptedOrder(53, TimeInForce.GTC, null));
        OrderLifecycle earlyDay = create(acceptedOrder(54, TimeInForce.DAY, null));
        OrderLifecycle missingClose = create(acceptedOrder(55, TimeInForce.DAY, null));
        OrderLifecycle earlyGtd = create(acceptedOrder(56, TimeInForce.GTD, T3));

        assertEquals(OrderStatus.EXPIRED, store.apply(expire(501, day, 1, T1, T1)).status());
        assertEquals("GTD_EXPIRY", store.apply(expire(502, gtd, 1, T2, T1)).terminalReason());
        assertThrows(IllegalStateException.class, () -> store.apply(expire(503, gtc, 1, T2, null)));
        assertThrows(IllegalArgumentException.class, () -> store.apply(expire(504, earlyDay, 1, T1, T2)));
        assertThrows(IllegalArgumentException.class, () -> store.apply(expire(505, missingClose, 1, T1, null)));
        assertThrows(IllegalArgumentException.class, () -> store.apply(expire(506, earlyGtd, 1, T2, T1)));

        for (OrderLifecycle unchanged : List.of(gtc, earlyDay, missingClose, earlyGtd)) {
            assertEquals(unchanged, snapshot(unchanged.orderId()));
            assertEquals(1, query.findTransitions(unchanged.orderId()).size());
            assertEquals(1, receiptCount(unchanged.orderId()));
        }
    }

    @Test
    void globalCommandReuseAcrossOrdersConflictsWithoutMutatingEitherOrder() {
        OrderLifecycle first = create(acceptedOrder(61, TimeInForce.DAY, null));
        OrderLifecycle second = create(acceptedOrder(62, TimeInForce.DAY, null));
        UUID reused = commandId(601);
        OrderLifecycle firstResult = store.apply(new FillOrderCommand(reused, first.orderId(), 1, new BigDecimal("2"), T1));

        assertThrows(OrderLifecycleConflictException.class, () -> store.apply(
                new FillOrderCommand(reused, second.orderId(), 1, new BigDecimal("2"), T1)));

        assertEquals(firstResult, snapshot(first.orderId()));
        assertEquals(second, snapshot(second.orderId()));
        assertEquals(1, query.findTransitions(second.orderId()).size());
    }

    @Test
    void historyConflictRollsBackReceiptAndSnapshotUpdate() {
        OrderLifecycle desired = create(acceptedOrder(63, TimeInForce.DAY, null));
        UUID occupiedCommand = commandId(631);
        UUID attemptedCommand = commandId(632);

        jdbcClient.sql("alter table trading.order_lifecycle_command drop constraint order_lifecycle_command_order_version_unique")
                .update();
        insertReceipt(occupiedCommand, desired.orderId(), 2, OrderStatus.PARTIALLY_FILLED, fingerprint('a'));
        jdbcClient.sql("""
                        insert into trading.order_lifecycle_transition (
                            order_id, version, command_id, from_status, to_status,
                            fill_delta, cumulative_filled_quantity, occurred_at, reason
                        ) values (
                            :orderId, 2, :commandId, 'ACCEPTED', 'PARTIALLY_FILLED',
                            1, 1, :occurredAt, null
                        )
                        """)
                .param("orderId", desired.orderId())
                .param("commandId", occupiedCommand)
                .param("occurredAt", T1.atOffset(java.time.ZoneOffset.UTC))
                .update();
        try {
            assertThrows(OrderLifecycleConflictException.class, () -> store.apply(
                    new FillOrderCommand(attemptedCommand, desired.orderId(), 1, BigDecimal.ONE, T1)));
            assertEquals(0, jdbcClient.sql("select count(*) from trading.order_lifecycle_command where command_id = :commandId")
                    .param("commandId", attemptedCommand)
                    .query(Integer.class)
                    .single());
            assertEquals(desired, snapshot(desired.orderId()));
        } finally {
            jdbcClient.sql("delete from trading.order_lifecycle_transition where command_id = :commandId")
                    .param("commandId", occupiedCommand)
                    .update();
            jdbcClient.sql("delete from trading.order_lifecycle_command where command_id = :commandId")
                    .param("commandId", occupiedCommand)
                    .update();
            jdbcClient.sql("""
                            alter table trading.order_lifecycle_command
                            add constraint order_lifecycle_command_order_version_unique
                            unique (order_id, resulting_version)
                            """)
                    .update();
        }

        assertEquals(desired, snapshot(desired.orderId()));
        assertEquals(List.of(1L), versions(desired.orderId()));
        assertEquals(1, receiptCount(desired.orderId()));
    }

    @Test
    void malformedStoredUuidStatusVersionAndFillStateFailExplicitly() {
        assertCorruptStateFails(71, "update trading.trading_order set create_command_id = '00000000-0000-4000-8000-000000000001'", List.of());
        assertCorruptStateFails(
                72,
                "update trading.trading_order set status = 'BROKEN'",
                List.of("trading_order_status_check", "trading_order_state_shape_check"));
        assertCorruptStateFails(
                73,
                "update trading.trading_order set version = 0",
                List.of("trading_order_version_check", "trading_order_state_shape_check"));
        assertCorruptStateFails(
                74,
                "update trading.trading_order set cumulative_filled_quantity = quantity + 1",
                List.of("trading_order_fill_bounds_check", "trading_order_state_shape_check"));
    }

    @Test
    void queryReturnsAbsentValuesAndHistoryInAscendingVersionOrder() {
        UUID absent = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        assertFalse(query.findByOrderId(absent).isPresent());
        assertTrue(query.findTransitions(absent).isEmpty());

        OrderLifecycle desired = create(acceptedOrder(81, TimeInForce.DAY, null));
        OrderLifecycle first = store.apply(fill(801, desired, 1, "2", T1));
        store.apply(cancel(802, first, 2, "DONE", T2));

        List<JooqOrderLifecycleQuery.TransitionView> transitions = query.findTransitions(desired.orderId());
        assertEquals(List.of(1L, 2L, 3L), transitions.stream().map(JooqOrderLifecycleQuery.TransitionView::version).toList());
        assertEquals(List.of(OrderStatus.ACCEPTED, OrderStatus.PARTIALLY_FILLED, OrderStatus.CANCELLED),
                transitions.stream().map(JooqOrderLifecycleQuery.TransitionView::toStatus).toList());
        assertEquals(new BigDecimal("2.000000000000000000"), transitions.get(1).fillDelta());
        assertEquals("DONE", transitions.get(2).reason());
    }

    @Test
    void databaseConstraintsRejectMalformedFingerprintEnumsNumericShapesAndState() {
        OrderLifecycle desired = create(acceptedOrder(82, TimeInForce.DAY, null));

        assertRejectedUpdate("request_fingerprint = 'ABCDEF'", desired.orderId());
        assertRejectedUpdate("side = 'HOLD'", desired.orderId());
        assertRejectedUpdate("quantity = 0", desired.orderId());
        assertRejectedUpdate("order_type = 'LIMIT'", desired.orderId());
        assertRejectedUpdate("time_in_force = 'GTD'", desired.orderId());
        assertRejectedUpdate("time_in_force = 'GTD', expires_at = created_at", desired.orderId());
        assertRejectedUpdate("version = 0", desired.orderId());
        assertRejectedUpdate("cumulative_filled_quantity = -1", desired.orderId());
        assertRejectedUpdate("status = 'CANCELLED', version = 2, terminal_reason = null", desired.orderId());

        assertEquals(desired, snapshot(desired.orderId()));
    }

    @Test
    void snapshotConstraintsRejectImpossibleExpirationStates() {
        OrderLifecycle gtc = create(acceptedOrder(87, TimeInForce.GTC, null));
        OrderLifecycle earlyGtd = create(acceptedOrder(88, TimeInForce.GTD, T2));
        OrderLifecycle wrongGtdReason = create(acceptedOrder(89, TimeInForce.GTD, T2));
        OrderLifecycle wrongDayReason = create(acceptedOrder(90, TimeInForce.DAY, null));

        assertRejectedUpdate(
                "status = 'EXPIRED', version = 2, last_transition_at = created_at + interval '1 minute', "
                        + "terminal_reason = 'GTD_EXPIRY'",
                gtc.orderId());
        assertRejectedUpdate(
                "status = 'EXPIRED', version = 2, last_transition_at = created_at + interval '1 minute', "
                        + "terminal_reason = 'GTD_EXPIRY'",
                earlyGtd.orderId());
        assertRejectedUpdate(
                "status = 'EXPIRED', version = 2, last_transition_at = expires_at, "
                        + "terminal_reason = 'DAY_SESSION_CLOSE'",
                wrongGtdReason.orderId());
        assertRejectedUpdate(
                "status = 'EXPIRED', version = 2, last_transition_at = created_at + interval '1 minute', "
                        + "terminal_reason = 'GTD_EXPIRY'",
                wrongDayReason.orderId());
    }

    @Test
    void lifecycleNumericColumnsUseExactPrecisionAndScale() {
        List<String> numericColumns = jdbcClient.sql("""
                        select table_name || '.' || column_name
                        from information_schema.columns
                        where table_schema = 'trading'
                          and table_name in ('trading_order', 'order_lifecycle_transition')
                          and data_type = 'numeric'
                          and numeric_precision = 38
                          and numeric_scale = 18
                        order by table_name, ordinal_position
                        """)
                .query(String.class)
                .list();

        assertEquals(List.of(
                "order_lifecycle_transition.fill_delta",
                "order_lifecycle_transition.cumulative_filled_quantity",
                "trading_order.quantity",
                "trading_order.limit_price",
                "trading_order.stop_price",
                "trading_order.trail_percent",
                "trading_order.cumulative_filled_quantity"), numericColumns);
    }

    @Test
    void commandAndTransitionConstraintsRejectLaterRejection() {
        OrderLifecycle desired = create(acceptedOrder(83, TimeInForce.DAY, null));
        assertThrows(DataIntegrityViolationException.class, () -> insertReceipt(
                commandId(831), desired.orderId(), 2, OrderStatus.REJECTED, fingerprint('b')));

        UUID transitionCommand = commandId(832);
        insertReceipt(transitionCommand, desired.orderId(), 2, OrderStatus.CANCELLED, fingerprint('c'));
        assertThrows(DataIntegrityViolationException.class, () -> jdbcClient.sql("""
                        insert into trading.order_lifecycle_transition (
                            order_id, version, command_id, from_status, to_status,
                            fill_delta, cumulative_filled_quantity, occurred_at, reason
                        ) values (
                            :orderId, 2, :commandId, 'ACCEPTED', 'REJECTED',
                            null, 0, :occurredAt, 'LATE_REJECTION'
                        )
                        """)
                .param("orderId", desired.orderId())
                .param("commandId", transitionCommand)
                .param("occurredAt", T1.atOffset(java.time.ZoneOffset.UTC))
                .update());
    }

    @Test
    void transitionMustMatchReceiptIdentityAndLocallyConsistentFillState() {
        OrderLifecycle receiptOwner = create(acceptedOrder(84, TimeInForce.DAY, null));
        OrderLifecycle differentOrder = create(acceptedOrder(85, TimeInForce.DAY, null));
        UUID mismatchedCommand = commandId(841);
        insertReceipt(mismatchedCommand, receiptOwner.orderId(), 2, OrderStatus.CANCELLED, fingerprint('d'));

        assertThrows(DataIntegrityViolationException.class, () -> jdbcClient.sql("""
                        insert into trading.order_lifecycle_transition (
                            order_id, version, command_id, from_status, to_status,
                            fill_delta, cumulative_filled_quantity, occurred_at, reason
                        ) values (
                            :differentOrderId, 2, :commandId, 'ACCEPTED', 'CANCELLED',
                            null, 0, :occurredAt, 'MISMATCHED_RECEIPT'
                        )
                        """)
                .param("differentOrderId", differentOrder.orderId())
                .param("commandId", mismatchedCommand)
                .param("occurredAt", T1.atOffset(java.time.ZoneOffset.UTC))
                .update());

        OrderLifecycle fillOrder = create(acceptedOrder(86, TimeInForce.DAY, null));
        UUID malformedFillCommand = commandId(842);
        insertReceipt(malformedFillCommand, fillOrder.orderId(), 2, OrderStatus.PARTIALLY_FILLED, fingerprint('e'));
        assertThrows(DataIntegrityViolationException.class, () -> jdbcClient.sql("""
                        insert into trading.order_lifecycle_transition (
                            order_id, version, command_id, from_status, to_status,
                            fill_delta, cumulative_filled_quantity, occurred_at, reason
                        ) values (
                            :orderId, 2, :commandId, 'ACCEPTED', 'PARTIALLY_FILLED',
                            1, 0, :occurredAt, null
                        )
                        """)
                .param("orderId", fillOrder.orderId())
                .param("commandId", malformedFillCommand)
                .param("occurredAt", T1.atOffset(java.time.ZoneOffset.UTC))
                .update());
    }

    @Test
    void transitionConstraintsRejectNullSourceAfterVersionOne() {
        OrderLifecycle desired = create(acceptedOrder(92, TimeInForce.DAY, null));
        UUID commandId = commandId(921);
        insertReceipt(commandId, desired.orderId(), 2, OrderStatus.PARTIALLY_FILLED, fingerprint('f'));

        assertThrows(DataIntegrityViolationException.class, () -> jdbcClient.sql("""
                        insert into trading.order_lifecycle_transition (
                            order_id, version, command_id, from_status, to_status,
                            fill_delta, cumulative_filled_quantity, occurred_at, reason
                        ) values (
                            :orderId, 2, :commandId, null, 'PARTIALLY_FILLED',
                            1, 1, :occurredAt, null
                        )
                        """)
                .param("orderId", desired.orderId())
                .param("commandId", commandId)
                .param("occurredAt", T1.atOffset(java.time.ZoneOffset.UTC))
                .update());
    }

    @Test
    void transitionConstraintsRejectImpossibleExpirationEvents() {
        OrderLifecycle gtc = create(acceptedOrder(93, TimeInForce.GTC, null));
        OrderLifecycle earlyGtd = create(acceptedOrder(94, TimeInForce.GTD, T2));
        OrderLifecycle wrongGtdReason = create(acceptedOrder(95, TimeInForce.GTD, T2));
        OrderLifecycle wrongDayReason = create(acceptedOrder(96, TimeInForce.DAY, null));

        assertRejectedExpirationTransition(gtc, 931, T1, "GTD_EXPIRY");
        assertRejectedExpirationTransition(earlyGtd, 941, T1, "GTD_EXPIRY");
        assertRejectedExpirationTransition(wrongGtdReason, 951, T2, "DAY_SESSION_CLOSE");
        assertRejectedExpirationTransition(wrongDayReason, 961, T1, "GTD_EXPIRY");
    }

    @Test
    void unexpectedInfrastructureFailurePropagatesAsDataAccessException() {
        OrderLifecycle desired = acceptedOrder(91, TimeInForce.DAY, null);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThrows(DataAccessException.class, () -> transaction.executeWithoutResult(status -> {
            jdbcClient.sql("alter table trading.trading_order rename to trading_order_unavailable").update();
            store.createOrLoad(desired);
        }));

        assertEquals(0, count("trading.trading_order"));
    }

    private static OrderLifecycle acceptedOrder() {
        return acceptedOrder(INTENT_ID, CANDIDATE_ID, INSTRUMENT_ID, TimeInForce.DAY, null);
    }

    private static OrderLifecycle acceptedOrder(int suffix, TimeInForce timeInForce, Instant expiresAt) {
        return acceptedOrder(id("10000000", suffix), id("20000000", suffix), INSTRUMENT_ID, timeInForce, expiresAt);
    }

    private static OrderLifecycle acceptedOrder(
            UUID intentId, UUID candidateId, UUID instrumentId, TimeInForce timeInForce, Instant expiresAt) {
        return new OrderLifecycleFactory().accepted(new OrderTerms(
                intentId,
                candidateId,
                instrumentId,
                OrderSide.BUY,
                new BigDecimal("10"),
                OrderType.MARKET,
                timeInForce,
                null,
                null,
                null,
                expiresAt), CREATED_AT);
    }

    private static OrderLifecycle rejectedOrder(int suffix) {
        return new OrderLifecycleFactory().rejected(new OrderTerms(
                id("10000000", suffix),
                id("20000000", suffix),
                INSTRUMENT_ID,
                OrderSide.SELL,
                new BigDecimal("10"),
                OrderType.MARKET,
                TimeInForce.GTC,
                null,
                null,
                null,
                null), CREATED_AT, "VALIDATION_REJECTED");
    }

    private static OrderLifecycle create(OrderLifecycle desired) {
        return store.createOrLoad(desired);
    }

    private static OrderLifecycle partial(OrderLifecycle current, int commandSuffix) {
        return store.apply(fill(commandSuffix, current, current.version(), "2", T1));
    }

    private static FillOrderCommand fill(
            int commandSuffix, OrderLifecycle current, long expectedVersion, String delta, Instant occurredAt) {
        return new FillOrderCommand(
                commandId(commandSuffix), current.orderId(), expectedVersion, new BigDecimal(delta), occurredAt);
    }

    private static CancelOrderCommand cancel(
            int commandSuffix, OrderLifecycle current, long expectedVersion, String reason, Instant occurredAt) {
        return new CancelOrderCommand(commandId(commandSuffix), current.orderId(), expectedVersion, reason, occurredAt);
    }

    private static ExpireOrderCommand expire(
            int commandSuffix,
            OrderLifecycle current,
            long expectedVersion,
            Instant occurredAt,
            Instant daySessionClose) {
        return new ExpireOrderCommand(
                commandId(commandSuffix), current.orderId(), expectedVersion, occurredAt, daySessionClose);
    }

    private static Callable<OrderLifecycle> concurrentApply(
            FillOrderCommand command, CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            start.await();
            return newStore().apply(command);
        };
    }

    private static OrderLifecycle snapshot(UUID orderId) {
        return query.findByOrderId(orderId).orElseThrow().toDomain();
    }

    private static List<Long> versions(UUID orderId) {
        return query.findTransitions(orderId).stream()
                .map(JooqOrderLifecycleQuery.TransitionView::version)
                .toList();
    }

    private static int receiptCount(UUID orderId) {
        return jdbcClient.sql("select count(*) from trading.order_lifecycle_command where order_id = :orderId")
                .param("orderId", orderId)
                .query(Integer.class)
                .single();
    }

    private static void insertReceipt(
            UUID commandId, UUID orderId, long version, OrderStatus status, String requestFingerprint) {
        int inserted = jdbcClient.sql("""
                        insert into trading.order_lifecycle_command (
                            command_id, request_fingerprint, order_id, resulting_version, result_status
                        ) values (
                            :commandId, :requestFingerprint, :orderId, :version, :status
                        )
                        """)
                .param("commandId", commandId)
                .param("requestFingerprint", requestFingerprint)
                .param("orderId", orderId)
                .param("version", version)
                .param("status", status.name())
                .update();
        assertEquals(1, inserted);
    }

    private static void assertRejectedExpirationTransition(
            OrderLifecycle current, int commandSuffix, Instant occurredAt, String reason) {
        UUID commandId = commandId(commandSuffix);
        insertReceipt(commandId, current.orderId(), 2, OrderStatus.EXPIRED, fingerprint('e'));
        assertThrows(DataIntegrityViolationException.class, () -> jdbcClient.sql("""
                        insert into trading.order_lifecycle_transition (
                            order_id, version, command_id, from_status, to_status,
                            fill_delta, cumulative_filled_quantity, occurred_at, reason
                        ) values (
                            :orderId, 2, :commandId, 'ACCEPTED', 'EXPIRED',
                            null, 0, :occurredAt, :reason
                        )
                        """)
                .param("orderId", current.orderId())
                .param("commandId", commandId)
                .param("occurredAt", occurredAt.atOffset(java.time.ZoneOffset.UTC))
                .param("reason", reason)
                .update());
    }

    private static void assertCorruptStateFails(int suffix, String update, List<String> constraintsToDrop) {
        OrderLifecycle desired = create(acceptedOrder(suffix, TimeInForce.DAY, null));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            for (String constraint : constraintsToDrop) {
                jdbcClient.sql("alter table trading.trading_order drop constraint " + constraint).update();
            }
            jdbcClient.sql(update + " where order_id = :orderId")
                    .param("orderId", desired.orderId())
                    .update();
            assertThrows(IllegalArgumentException.class, () -> newStore().createOrLoad(desired));
            status.setRollbackOnly();
        });
        assertEquals(desired, snapshot(desired.orderId()));
    }

    private static void assertRejectedUpdate(String assignment, UUID orderId) {
        assertThrows(DataIntegrityViolationException.class, () -> jdbcClient.sql(
                        "update trading.trading_order set " + assignment + " where order_id = :orderId")
                .param("orderId", orderId)
                .update());
    }

    private static UUID commandId(int suffix) {
        return id("90000000", suffix);
    }

    private static UUID id(String prefix, int suffix) {
        return UUID.fromString(prefix + "-0000-0000-0000-" + "%012x".formatted(suffix));
    }

    private static String fingerprint(char value) {
        return String.valueOf(value).repeat(64);
    }

    private static PostgresOrderLifecycleStore newStore() {
        return new PostgresOrderLifecycleStore(JdbcClient.create(dataSource), transactionManager);
    }

    private static int count(String table) {
        return jdbcClient.sql("select count(*) from " + table).query(Integer.class).single();
    }
}
