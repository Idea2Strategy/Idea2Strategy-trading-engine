package com.idea2strategy.trading.worker.candidate;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.idea2strategy.trading.application.candidate.ScopedCandidateComposer;
import com.idea2strategy.trading.application.candidate.ScopedCompositionResult;
import com.idea2strategy.trading.domain.candidate.CandidateBatch;
import com.idea2strategy.trading.messaging.evaluation.OrderCandidate;
import com.idea2strategy.trading.messaging.evaluation.OrderCandidateBatch;
import com.idea2strategy.trading.messaging.evaluation.OrderSide;
import com.idea2strategy.trading.messaging.fixture.v1.ContractJsonFixtureLoaderV1;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * RT4: the production composition of a scoped candidate batch, proven on the real canonical schema.
 *
 * <p>C's own version 2 contract fixture — the same bytes {@code CandidateHandoffExactlyOnceE2ETest}
 * proves exactly-once claiming over — lands here as one canonical intent batch, one accepted
 * canonical order per executable intent, and one attached buying-power reservation, and a second
 * delivery of the same batch adds not a single row. A second batch overreaches the remaining budget
 * and is REDUCED with the reason persisted on {@code trading.order_intents}, which is the row the
 * F17 read projections expose.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.table=flyway_schema_history_private",
        "spring.flyway.baseline-on-migrate=true"
})
class ScopedCandidateCompositionE2ETest {

    private static final UUID FIXTURE_BOT = UUID.fromString("e332fd66-3a21-4d3e-8a2a-4c2e4ee55430");
    private static final UUID FIXTURE_PARTITION = UUID.fromString("1f0a5b6c-8d2e-4a71-9c33-2b5e7d901aa4");
    private static final UUID FIXTURE_FLOW = UUID.fromString("9b8a7c6d-5e4f-4a3b-9c2d-1e0f9a8b7c6d");
    private static final UUID FIXTURE_EVENT = UUID.fromString("7c1d2e3f-4a5b-4c6d-8e9f-0a1b2c3d4e5f");
    private static final UUID FIXTURE_INSTRUMENT = UUID.fromString("8a35e6b5-cf84-4f63-920d-57c1f1b95df0");
    private static final UUID FEE_POLICY = UUID.fromString("41000000-0000-4000-8000-000000000001");
    private static final UUID BUFFER_POLICY = UUID.fromString("41000000-0000-4000-8000-000000000002");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        com.idea2strategy.trading.persistence.canonical.CanonicalBaseline
                .migrateWithContributions(dataSource);
        seed(dataSource);

        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static void seed(DriverManagerDataSource dataSource) {
        try (java.sql.Connection connection = dataSource.getConnection();
                java.sql.Statement statement = connection.createStatement()) {
            statement.execute("set session_replication_role = replica");
            statement.addBatch("""
                    insert into identity.accounts (id, lifecycle_status, status_changed_at, created_at)
                    values ('41a00000-0000-4000-8000-000000000001', 'ACTIVE',
                        '2026-07-01T00:00:00+00', '2026-07-01T00:00:00+00')
                    """);
            statement.addBatch("""
                    insert into bot.bots (id, owner_account_id, mode, name, lifecycle_status,
                        lifecycle_changed_at, created_at, execution_eligible_from)
                    values ('%s', '41a00000-0000-4000-8000-000000000001', 'BASIC', 'RT4 bot',
                        'RUNNING', '2026-07-01T00:00:00+00', '2026-07-01T00:00:00+00',
                        '2026-07-01T00:00:00+00')
                    """.formatted(FIXTURE_BOT));
            statement.addBatch("""
                    insert into trading.fee_policy_versions (id, policy_code, version, fee_rate_bps,
                        calculation_rules_version, rules_hash, effective_from, published_at)
                    values ('%s', 'OFFICIAL', 'rt4', 20, 'v1', 'sha256:rt4-fee',
                        '2026-07-01T00:00:00+00', '2026-07-01T00:00:00+00')
                    """.formatted(FEE_POLICY));
            statement.addBatch("""
                    insert into trading.buying_power_buffer_policy_versions (id, policy_code,
                        version, buffer_bps, rounding_rules_version, rules_hash, effective_from,
                        published_at)
                    values ('%s', 'OFFICIAL', 'rt4', 100, 'v1', 'sha256:rt4-buffer',
                        '2026-07-01T00:00:00+00', '2026-07-01T00:00:00+00')
                    """.formatted(BUFFER_POLICY));
            statement.addBatch("""
                    insert into bot.launch_configurations (bot_id, initial_cash_amount,
                        currency_code, broker_rules_version, accounting_rules_version,
                        precision_rules_version, fee_policy_id, slippage_rate_bps,
                        buying_power_buffer_policy_id, candidate_conflict_policy,
                        configuration_hash)
                    values ('%s', 100000, 'USD', 'broker-rules:v1', 'accounting-rules:v1',
                        'precision-rules:v1', '%s', 5, '%s', '{}', '%s')
                    """.formatted(FIXTURE_BOT, FEE_POLICY, BUFFER_POLICY, "c".repeat(64)));
            statement.addBatch("""
                    insert into bot.bot_partitions (id, bot_id, name, budget_cap_bps,
                        position_x, position_y, configuration_hash)
                    values ('%s', '%s', 'Partition', 10000, 0, 0, '%s')
                    """.formatted(FIXTURE_PARTITION, FIXTURE_BOT, "c".repeat(64)));
            statement.addBatch("""
                    insert into bot.flows (id, partition_id, name, element_catalog_version_id,
                        compiled_flow_plan_id, position_x, position_y, semantic_document,
                        layout_document, layout_schema_version, semantic_hash, layout_hash,
                        configuration_hash)
                    values ('%s', '%s', 'Flow', gen_random_uuid(), gen_random_uuid(), 0, 0,
                        '{}', '{}', 'v1', '%s', '%s', '%s')
                    """.formatted(FIXTURE_FLOW, FIXTURE_PARTITION,
                            "a".repeat(64), "b".repeat(64), "c".repeat(64)));
            statement.addBatch("""
                    insert into bot.bot_events (id, bot_id, event_sequence, event_type,
                        event_schema_version, correlation_id, idempotency_key, occurred_at,
                        received_at, summary_document)
                    values ('%s', '%s', 1, 'ORDER_LIFECYCLE', 'v1', gen_random_uuid(),
                        'rt4-source-event', '2026-07-31T14:30:00+00', '2026-07-31T14:30:00+00', '{}')
                    """.formatted(FIXTURE_EVENT, FIXTURE_BOT));
            statement.addBatch("""
                    insert into market_data.instruments (id, asset_type, primary_exchange_mic,
                        currency_code)
                    values ('%s', 'STOCK', 'XNAS', 'USD')
                    """.formatted(FIXTURE_INSTRUMENT));
            statement.addBatch("""
                    insert into bot.evaluation_runs (id, bot_id, partition_id, flow_id,
                        trigger_event_id, status, queued_at)
                    values ('626825b7-9de7-447a-a775-d8840fd24e55', '%s', '%s', '%s', '%s',
                        'RUNNING', '2026-07-31T14:30:00+00')
                    """.formatted(FIXTURE_BOT, FIXTURE_PARTITION, FIXTURE_FLOW, FIXTURE_EVENT));
            statement.addBatch("""
                    insert into bot.bot_events (id, bot_id, event_sequence, event_type,
                        event_schema_version, correlation_id, idempotency_key, occurred_at,
                        received_at, summary_document)
                    values ('47e00000-0000-4000-8000-00000000000e', '%s', 2, 'ORDER_LIFECYCLE',
                        'v1', gen_random_uuid(), 'rt4-over-budget-event',
                        '2026-07-31T14:35:00+00', '2026-07-31T14:35:00+00', '{}')
                    """.formatted(FIXTURE_BOT));
            statement.addBatch("""
                    insert into bot.evaluation_runs (id, bot_id, partition_id, flow_id,
                        trigger_event_id, status, queued_at)
                    values ('%s', '%s', '%s', '%s', '47e00000-0000-4000-8000-00000000000e',
                        'RUNNING', '2026-07-31T14:35:00+00')
                    """.formatted(
                            UUID.nameUUIDFromBytes(("rt4-evaluation:"
                                    + "47b00000-0000-4000-8000-00000000000b")
                                    .getBytes(StandardCharsets.UTF_8)),
                            FIXTURE_BOT, FIXTURE_PARTITION, FIXTURE_FLOW));
            // The bot has 100000 available and this partition allows all of it. The fixture's buy
            // (2 x 210.12 limit) costs ~425 with slippage, fee and buffer, so it is APPROVED; the
            // over-budget batch below asks for vastly more than remains and must be REDUCED.
            statement.addBatch("""
                    insert into trading.bot_budget_projections (bot_id, currency_code,
                        available_cash_amount, active_reservation_amount, invested_amount,
                        segregated_short_proceeds_amount, short_collateral_amount, valuation_at,
                        valuation_status, last_event_sequence, projection_hash, updated_at)
                    values ('%s', 'USD', 100000, 0, 0, 0, 0, '2026-07-31T14:00:00+00', 'VALUED', 1,
                        'rt4-bot-budget', '2026-07-31T14:00:00+00')
                    """.formatted(FIXTURE_BOT));
            statement.addBatch("""
                    insert into trading.partition_budget_projections (partition_id, bot_id,
                        currency_code, budget_cap_amount, active_reservation_amount,
                        invested_amount, segregated_short_proceeds_amount, short_collateral_amount,
                        valuation_at, valuation_status, last_event_sequence, projection_hash,
                        updated_at)
                    values ('%s', '%s', 'USD', 100000, 0, 0, 0, 0, '2026-07-31T14:00:00+00',
                        'VALUED', 1, 'rt4-partition-budget', '2026-07-31T14:00:00+00')
                    """.formatted(FIXTURE_PARTITION, FIXTURE_BOT));
            statement.executeBatch();
            statement.execute("set session_replication_role = origin");
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("unable to seed the composition's parents", failure);
        }
    }

    @Autowired
    private ScopedCandidateComposer composer;

    @Autowired
    private JdbcClient jdbc;

    private final OrderCandidateBatchAdapter adapter = new OrderCandidateBatchAdapter();

    /** C's fixture composed twice: one intent batch, one order, one attached reservation. */
    @Test
    void composesTheContractFixtureOnceUnderRedelivery() {
        CandidateBatch batch = adapter.toDomain(ContractJsonFixtureLoaderV1.readResource(
                "contracts/v2/order-candidate-batch.json",
                new TypeReference<OrderCandidateBatch>() {}));
        assertTrue(batch.carriesPartitionScope());

        ScopedCompositionResult first = composer.compose(batch);
        ScopedCompositionResult redelivered = composer.compose(batch);

        UUID intentBatchId = first.intentBatchId();
        assertAll(
                () -> assertEquals(intentBatchId, redelivered.intentBatchId()),
                () -> assertEquals(1, first.approved()),
                () -> assertEquals(0, first.reduced()),
                () -> assertEquals(0, first.rejected()),
                () -> assertEquals(1, first.ordersComposed()),
                () -> assertEquals(first.approved(), redelivered.approved()),
                () -> assertEquals(1, count(
                        "select count(*) from trading.order_intent_batches where id = ?",
                        intentBatchId)),
                () -> assertEquals(1, count(
                        "select count(*) from trading.order_intents where batch_id = ?",
                        intentBatchId)),
                // Canonical OPEN with nothing filled is the domain's ACCEPTED.
                () -> assertEquals(1, count(
                        "select count(*) from trading.order_state_projections "
                                + "where bot_id = ? and status = 'OPEN'", FIXTURE_BOT)),
                () -> assertEquals(1, count(
                        "select count(*) from trading.resource_reservations r "
                                + "join trading.order_intents i on i.id = r.intent_id "
                                + "where i.batch_id = ?", intentBatchId)),
                () -> assertEquals(1, count(
                        "select count(*) from trading.order_component_reservations link "
                                + "join trading.resource_reservations r on r.id = link.reservation_id "
                                + "join trading.order_intents i on i.id = r.intent_id "
                                + "where i.batch_id = ?", intentBatchId)));
    }

    /**
     * A buy the budget cannot carry whole is REDUCED proportionally and says so on the canonical
     * intent row — the row F17's read projections expose.
     */
    @Test
    void reducesAnOverBudgetBuyAndPersistsTheReason() {
        UUID batchId = UUID.fromString("47b00000-0000-4000-8000-00000000000b");
        UUID candidateId = UUID.nameUUIDFromBytes(
                ("rt4-over-budget:" + batchId).getBytes(StandardCharsets.UTF_8));
        CandidateBatch batch = adapter.toDomain(new OrderCandidateBatch(
                OrderCandidateBatch.SCOPED_SCHEMA_VERSION,
                batchId,
                UUID.nameUUIDFromBytes(("rt4-evaluation:" + batchId).getBytes(StandardCharsets.UTF_8)),
                FIXTURE_BOT,
                FIXTURE_PARTITION,
                UUID.fromString("47e00000-0000-4000-8000-00000000000e"),
                Instant.parse("2026-07-31T14:35:00Z"),
                List.of(new OrderCandidate(
                        candidateId,
                        FIXTURE_INSTRUMENT,
                        FIXTURE_FLOW,
                        OrderSide.BUY,
                        new BigDecimal("1000"),
                        new BigDecimal("200"),
                        List.of("BASIC_RULE_MATCHED")))));

        ScopedCompositionResult first = composer.compose(batch);
        ScopedCompositionResult redelivered = composer.compose(batch);

        assertAll(
                () -> assertEquals(1, first.reduced()),
                () -> assertEquals(0, first.approved()),
                () -> assertEquals(1, first.ordersComposed()),
                () -> assertEquals(first.intentBatchId(), redelivered.intentBatchId()),
                () -> assertEquals("REDUCED", text(
                        "select decision::text from trading.order_intents where intent_key = ?",
                        "candidate:" + candidateId)),
                () -> assertEquals("COMMON_FUNDS_PROPORTIONAL_REDUCTION", text(
                        "select decision_reason_code from trading.order_intents "
                                + "where intent_key = ?", "candidate:" + candidateId)),
                () -> assertTrue(new BigDecimal(text(
                        "select final_quantity::text from trading.order_intents "
                                + "where intent_key = ?", "candidate:" + candidateId))
                        .compareTo(new BigDecimal("1000")) < 0),
                () -> assertEquals(1, count(
                        "select count(*) from trading.resource_reservations "
                                + "where intent_id = (select id from trading.order_intents "
                                + "where intent_key = ?)", "candidate:" + candidateId)));
    }

    private long count(String sql, Object argument) {
        return jdbc.sql(sql).param(argument).query(Long.class).single();
    }

    private String text(String sql, Object argument) {
        return jdbc.sql(sql).param(argument).query(String.class).single();
    }
}
