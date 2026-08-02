package com.idea2strategy.trading.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idea2strategy.trading.application.candidate.CandidateBatchClaimLostException;
import com.idea2strategy.trading.application.order.FillOrderCommand;
import com.idea2strategy.trading.application.port.CandidateBatchStatusPort;
import com.idea2strategy.trading.domain.candidate.CandidateBatch;
import com.idea2strategy.trading.domain.eligibility.OrderPositionEffect;
import com.idea2strategy.trading.domain.intent.IntentDecision;
import com.idea2strategy.trading.domain.intent.OrderIntentBatch;
import com.idea2strategy.trading.domain.intent.OrderIntentBatchFactory;
import com.idea2strategy.trading.domain.intent.OrderIntentBatchRequest;
import com.idea2strategy.trading.domain.intent.OrderIntentRequest;
import com.idea2strategy.trading.domain.order.OrderLifecycle;
import com.idea2strategy.trading.domain.order.OrderLifecycleFactory;
import com.idea2strategy.trading.domain.order.OrderSide;
import com.idea2strategy.trading.domain.order.OrderTerms;
import com.idea2strategy.trading.domain.order.OrderType;
import com.idea2strategy.trading.domain.order.TimeInForce;
import com.idea2strategy.trading.persistence.candidate.CandidateBatchProcessingStatus;
import com.idea2strategy.trading.persistence.candidate.JooqCandidateBatchQuery;
import com.idea2strategy.trading.persistence.candidate.PostgresCandidateBatchClaimAdapter;
import com.idea2strategy.trading.persistence.canonical.CanonicalBaseline;
import com.idea2strategy.trading.persistence.intent.PostgresOrderIntentBatchStore;
import com.idea2strategy.trading.persistence.order.JooqOrderLifecycleQuery;
import com.idea2strategy.trading.persistence.order.PostgresOrderLifecycleStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "trading.fake-candidate.enabled=true",
        "spring.flyway.enabled=true",
        // The private compatibility migrations keep their own history table. The canonical baseline
        // is migrated first under the default one, and two Flyway runs cannot share a history.
        "spring.flyway.table=flyway_schema_history_private",
        "spring.flyway.baseline-on-migrate=true"
})
class TradingWorkerApplicationTest {
    private static final String FAKE_BATCH_ID = "81000000-0000-0000-0000-000000000001";

    private static final UUID INTENT_BOT = UUID.fromString("a1000000-0000-0000-0000-000000000001");
    private static final UUID INTENT_PARTITION = UUID.fromString("a5000000-0000-0000-0000-000000000005");
    private static final UUID INTENT_EVENT = UUID.fromString("a6000000-0000-0000-0000-000000000006");
    private static final UUID INTENT_EVALUATION = UUID.fromString("a2000000-0000-0000-0000-000000000002");
    private static final UUID INTENT_SOURCE_BATCH = UUID.fromString("a3000000-0000-0000-0000-000000000003");
    private static final UUID INTENT_CANDIDATE = UUID.fromString("a4000000-0000-0000-0000-000000000004");
    private static final UUID INTENT_FLOW = UUID.fromString("a7000000-0000-0000-0000-000000000007");
    private static final UUID INTENT_INSTRUMENT = UUID.fromString("a8000000-0000-0000-0000-000000000008");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    /**
     * Stands the canonical schema up before the application context exists.
     *
     * <p>Stores are moving to the canonical tables one at a time, so this database has to carry both
     * schemas during the migration. The canonical baseline goes first because the application's own
     * Flyway run is what creates the remaining private compatibility tables on top of it.
     */
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        CanonicalBaseline.migrateWithContributions(dataSource);
        seedForeignServiceRows(dataSource);

        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * Bot, flow, evaluation and instrument rows belong to other services. They are seeded with
     * referential triggers off, exactly as the canonical contract fixtures do, so the canonical
     * foreign keys on the intent write are satisfied by real parents.
     */
    private static void seedForeignServiceRows(DriverManagerDataSource dataSource) {
        try (java.sql.Connection connection = dataSource.getConnection();
                java.sql.Statement statement = connection.createStatement()) {
            statement.execute("set session_replication_role = replica");
            statement.addBatch("""
                    insert into bot.bots (id, owner_account_id, mode, name, lifecycle_status,
                        lifecycle_changed_at, created_at, execution_eligible_from)
                    values ('%s', 'aa000000-0000-0000-0000-0000000000aa', 'BASIC', 'Worker bot',
                        'RUNNING', '2026-08-01T00:00:00+00', '2026-08-01T00:00:00+00',
                        '2026-08-01T00:00:00+00')
                    """.formatted(INTENT_BOT));
            statement.addBatch("""
                    insert into bot.bot_partitions (id, bot_id, name, budget_cap_bps,
                        position_x, position_y, configuration_hash)
                    values ('%s', '%s', 'Partition', 5000, 0, 0, '%s')
                    """.formatted(INTENT_PARTITION, INTENT_BOT, "c".repeat(64)));
            statement.addBatch("""
                    insert into bot.flows (id, partition_id, name, element_catalog_version_id,
                        compiled_flow_plan_id, position_x, position_y, semantic_document,
                        layout_document, layout_schema_version, semantic_hash, layout_hash,
                        configuration_hash)
                    values ('%s', '%s', 'Flow', gen_random_uuid(), gen_random_uuid(), 0, 0,
                        '{}', '{}', 'v1', '%s', '%s', '%s')
                    """.formatted(INTENT_FLOW, INTENT_PARTITION,
                            "a".repeat(64), "b".repeat(64), "c".repeat(64)));
            statement.addBatch("""
                    insert into bot.bot_events (id, bot_id, event_sequence, event_type,
                        event_schema_version, correlation_id, idempotency_key, occurred_at,
                        received_at, summary_document)
                    values ('%s', '%s', 1, 'EVALUATION_COMPLETED', 'v1', gen_random_uuid(),
                        'worker-seed-1', '2026-08-01T00:00:00+00', '2026-08-01T00:00:00+00', '{}')
                    """.formatted(INTENT_EVENT, INTENT_BOT));
            statement.addBatch("""
                    insert into bot.evaluation_runs (id, bot_id, partition_id, flow_id,
                        trigger_event_id, status, queued_at)
                    values ('%s', '%s', '%s', '%s', '%s', 'RUNNING', '2026-08-01T00:00:00+00')
                    """.formatted(INTENT_EVALUATION, INTENT_BOT, INTENT_PARTITION, INTENT_FLOW,
                            INTENT_EVENT));
            statement.addBatch("""
                    insert into market_data.instruments (id, asset_type, primary_exchange_mic,
                        currency_code)
                    values ('%s', 'STOCK', 'XNAS', 'USD')
                    """.formatted(INTENT_INSTRUMENT));
            statement.executeBatch();
            statement.execute("set session_replication_role = origin");
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("unable to seed foreign service rows", failure);
        }
    }

    @Autowired
    private JooqCandidateBatchQuery query;

    @Autowired
    private PostgresCandidateBatchClaimAdapter claimAdapter;

    @Autowired
    private CandidateBatchStatusPort statusPort;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PostgresOrderIntentBatchStore orderIntentBatchStore;

    @Autowired
    private PostgresOrderLifecycleStore orderLifecycleStore;

    @Autowired
    private JooqOrderLifecycleQuery orderLifecycleQuery;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void startsIndependentlyAndConsumesFakeCandidateBatch() {
        var processing = query.findByBatchId(java.util.UUID.fromString(FAKE_BATCH_ID)).orElseThrow();

        assertEquals(CandidateBatchProcessingStatus.COMPLETED, processing.status());
    }

    @Test
    void recordsFailedBatchThroughJpaStatusAdapter() {
        UUID batchId = UUID.fromString("91000000-0000-0000-0000-000000000001");
        CandidateBatch batch = new CandidateBatch(
                batchId,
                UUID.fromString("92000000-0000-0000-0000-000000000002"),
                Instant.parse("2026-08-01T00:01:00Z"),
                List.of());

        var claim = claimAdapter.claim(batch).orElseThrow();
        statusPort.fail(claim, "simulated downstream failure");

        var processing = query.findByBatchId(batchId).orElseThrow();
        assertEquals(CandidateBatchProcessingStatus.FAILED, processing.status());
        assertEquals("simulated downstream failure", processing.failureReason());
    }

    @Test
    void reclaimedBatchRejectsCompletionFromPreviousClaimant() {
        UUID batchId = UUID.fromString("93000000-0000-0000-0000-000000000003");
        CandidateBatch batch = new CandidateBatch(
                batchId,
                UUID.fromString("94000000-0000-0000-0000-000000000004"),
                Instant.parse("2026-08-01T00:02:00Z"),
                List.of());
        var previous = claimAdapter.claim(batch).orElseThrow();
        jdbcClient.sql("""
                        update trading.candidate_batch_processing
                        set lease_expires_at = current_timestamp - interval '1 minute'
                        where batch_id = :batchId
                        """)
                .param("batchId", batchId)
                .update();
        var current = claimAdapter.claim(batch).orElseThrow();

        assertFalse(claimAdapter.renew(previous));
        assertThrows(CandidateBatchClaimLostException.class, () -> statusPort.complete(previous));
        statusPort.complete(current);
        assertEquals(CandidateBatchProcessingStatus.COMPLETED, query.findByBatchId(batchId).orElseThrow().status());
    }

    @Test
    void exactIntentRetryUsesAutoConfiguredJpaTransactionManagerInsideOuterTransaction() {
        assertInstanceOf(JpaTransactionManager.class, transactionManager);
        OrderIntentBatch desired = new OrderIntentBatchFactory().create(new OrderIntentBatchRequest(
                INTENT_BOT,
                INTENT_PARTITION,
                INTENT_EVENT,
                INTENT_EVALUATION,
                INTENT_SOURCE_BATCH,
                Instant.parse("2026-08-01T00:00:00Z"),
                List.of(new OrderIntentRequest(
                        INTENT_CANDIDATE,
                        INTENT_FLOW,
                        INTENT_INSTRUMENT,
                        OrderSide.BUY,
                        OrderPositionEffect.INCREASE_LONG,
                        OrderType.MARKET,
                        TimeInForce.DAY,
                        new BigDecimal("2"),
                        null,
                        null,
                        null,
                        IntentDecision.APPROVED,
                        "ELIGIBLE",
                        new BigDecimal("2")))));
        TransactionTemplate outerTransaction = new TransactionTemplate(transactionManager);

        Integer subsequentQueryResult = outerTransaction.execute(status -> {
            assertEquals(desired, orderIntentBatchStore.createOrLoad(desired));
            assertEquals(desired, orderIntentBatchStore.createOrLoad(desired));
            return jdbcClient.sql("select 1").query(Integer.class).single();
        });

        assertEquals(1, subsequentQueryResult);
    }

    @Test
    void exactLifecycleReplayUsesAutoConfiguredJpaTransactionManagerInsideOuterTransaction() {
        assertInstanceOf(JpaTransactionManager.class, transactionManager);
        OrderLifecycle desired = new OrderLifecycleFactory().accepted(new OrderTerms(
                UUID.fromString("b1000000-0000-0000-0000-000000000001"),
                UUID.fromString("b2000000-0000-0000-0000-000000000002"),
                UUID.fromString("b3000000-0000-0000-0000-000000000003"),
                OrderSide.BUY,
                new BigDecimal("5"),
                OrderType.MARKET,
                TimeInForce.DAY,
                null,
                null,
                null,
                null), Instant.parse("2026-08-01T01:00:00Z"));
        FillOrderCommand partialFill = new FillOrderCommand(
                UUID.fromString("b4000000-0000-0000-0000-000000000004"),
                desired.orderId(),
                1,
                new BigDecimal("2"),
                Instant.parse("2026-08-01T01:01:00Z"));
        TransactionTemplate outerTransaction = new TransactionTemplate(transactionManager);

        Integer subsequentQueryResult = outerTransaction.execute(status -> {
            assertEquals(desired, orderLifecycleStore.createOrLoad(desired));
            OrderLifecycle partial = orderLifecycleStore.apply(partialFill);
            assertEquals(partial, orderLifecycleStore.apply(partialFill));
            return jdbcClient.sql("select 1").query(Integer.class).single();
        });

        assertEquals(1, subsequentQueryResult);
        assertEquals(List.of(1L, 2L), orderLifecycleQuery.findTransitions(desired.orderId()).stream()
                .map(JooqOrderLifecycleQuery.TransitionView::version)
                .toList());
    }

    @Test
    void lifecycleWritesEnlistInCallerOwnedJpaTransactionRollback() {
        assertInstanceOf(JpaTransactionManager.class, transactionManager);
        OrderLifecycle desired = new OrderLifecycleFactory().accepted(new OrderTerms(
                UUID.fromString("c1000000-0000-0000-0000-000000000001"),
                UUID.fromString("c2000000-0000-0000-0000-000000000002"),
                UUID.fromString("c3000000-0000-0000-0000-000000000003"),
                OrderSide.BUY,
                new BigDecimal("5"),
                OrderType.MARKET,
                TimeInForce.DAY,
                null,
                null,
                null,
                null), Instant.parse("2026-08-01T02:00:00Z"));
        FillOrderCommand partialFill = new FillOrderCommand(
                UUID.fromString("c4000000-0000-0000-0000-000000000004"),
                desired.orderId(),
                1,
                new BigDecimal("2"),
                Instant.parse("2026-08-01T02:01:00Z"));
        TransactionTemplate outerTransaction = new TransactionTemplate(transactionManager);

        outerTransaction.executeWithoutResult(status -> {
            orderLifecycleStore.createOrLoad(desired);
            orderLifecycleStore.apply(partialFill);
            status.setRollbackOnly();
        });

        assertFalse(orderLifecycleQuery.findByOrderId(desired.orderId()).isPresent());
        assertTrue(orderLifecycleQuery.findTransitions(desired.orderId()).isEmpty());
        assertEquals(0, jdbcClient.sql("""
                        select count(*) from trading.order_lifecycle_command where order_id = :orderId
                        """)
                .param("orderId", desired.orderId())
                .query(Integer.class)
                .single());
    }
}
