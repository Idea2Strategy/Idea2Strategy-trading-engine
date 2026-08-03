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

    // ------------------------------------------------------------------ #197 share sizing

    /**
     * The share becomes a quantity here, and the quantity accounts for what the order really costs.
     *
     * <p>Spendable cash is 100000 and the policies are 20 bps fee, 100 bps buffer, plus the fixed
     * 5 bps slippage, so one share at 200 costs {@code 200 × 1.0125 = 202.50}. The whole share
     * therefore affords {@code floor(100000 / 202.50) = 493}.
     *
     * <p>493 rather than 500 is the point. Sizing on the bare price would say 500, and 500 shares
     * would cost 101250 — more than the partition has — so the reservation would fail after the
     * order had already been accepted. Asserting 493 asserts that the fee, the buffer and the
     * slippage are all inside the sizing.
     */
    @Test
    void sizesAWholeShareAgainstTheTrueCostPerShare() {
        UUID candidateId = composeAllocatedBuy("share-whole", 1, 1, new BigDecimal("200"));

        assertAll(
                () -> assertEquals("APPROVED", decisionOf(candidateId)),
                () -> assertEquals(0, new BigDecimal("493").compareTo(finalQuantityOf(candidateId))),
                () -> assertEquals(0, new BigDecimal("493").compareTo(requestedQuantityOf(candidateId))));
    }

    /** A quarter share claims a quarter of the same budget: floor(25000 / 202.50) = 123. */
    @Test
    void sizesAFractionalShareFromItsOwnSliceOfTheBudget() {
        UUID candidateId = composeAllocatedBuy("share-quarter", 1, 4, new BigDecimal("200"));

        assertAll(
                () -> assertEquals("APPROVED", decisionOf(candidateId)),
                () -> assertEquals(0, new BigDecimal("123").compareTo(finalQuantityOf(candidateId))));
    }

    /**
     * The share stays an exact fraction until it meets the budget.
     *
     * <p>A third of 100000 at 101.25 per share affords {@code floor(33333.33… / 101.25) = 329}. Had
     * the contract pre-divided {@code 1/3} into a decimal — 0.33 — the budget would have been 33000
     * and the answer 325. The two integers exist so that cannot happen.
     */
    @Test
    void keepsTheShareExactRatherThanPreDividingIt() {
        UUID candidateId = composeAllocatedBuy("share-third", 1, 3, new BigDecimal("100"));

        assertEquals(0, new BigDecimal("329").compareTo(finalQuantityOf(candidateId)));
    }

    /**
     * Rounded down, never up. A whole share of 100000 at 101.25 affords 987.65…, and the 0.65 of a
     * share is money the allocation was not given.
     */
    @Test
    void roundsDownToWholeShares() {
        UUID candidateId = composeAllocatedBuy("share-rounding", 1, 1, new BigDecimal("100"));

        BigDecimal quantity = finalQuantityOf(candidateId);
        assertAll(
                () -> assertEquals(0, new BigDecimal("987").compareTo(quantity)),
                () -> assertEquals(0, quantity.stripTrailingZeros().scale(),
                        "no instrument is fractional-enabled, so a sized buy is whole shares"));
    }

    /** A share that cannot afford one whole share buys nothing, and says which budget ran out. */
    @Test
    void rejectsAShareThatAffordsNoWholeShare() {
        UUID candidateId = composeAllocatedBuy("share-too-small", 1, 1, new BigDecimal("200000"));

        assertAll(
                () -> assertEquals("REJECTED", decisionOf(candidateId)),
                () -> assertEquals("NO_AVAILABLE_SHARED_FUNDS", reasonOf(candidateId)),
                () -> assertEquals(0, count(
                        "select count(*) from trading.resource_reservations where intent_id = "
                                + "(select id from trading.order_intents where intent_key = ?)",
                        "candidate:" + candidateId)));
    }

    /**
     * Without a complete valuation a share has nothing to be a share of, so F02's fail-closed rule
     * applies to the sizing itself rather than only to the affordability check after it.
     */
    @Test
    void rejectsAShareWhenTheBudgetIsNotValued() {
        jdbc.sql("update trading.bot_budget_projections set valuation_status = 'STALE' "
                        + "where bot_id = :bot")
                .param("bot", FIXTURE_BOT)
                .update();
        try {
            UUID candidateId = composeAllocatedBuy("share-unvalued", 1, 1, new BigDecimal("200"));

            assertAll(
                    () -> assertEquals("REJECTED", decisionOf(candidateId)),
                    () -> assertEquals("POSITION_VALUATION_UNAVAILABLE", reasonOf(candidateId)));
        } finally {
            jdbc.sql("update trading.bot_budget_projections set valuation_status = 'VALUED' "
                            + "where bot_id = :bot")
                    .param("bot", FIXTURE_BOT)
                    .update();
        }
    }

    /** A version 3 sell states no size, and a bot holding nothing sells nothing. */
    @Test
    void rejectsASellWithNoOpenPosition() {
        UUID batchId = derived("share-sell-empty");
        UUID candidateId = derived("share-sell-empty-candidate");
        CandidateBatch batch = adapter.toDomain(new OrderCandidateBatch(
                OrderCandidateBatch.ALLOCATION_SCHEMA_VERSION,
                batchId,
                derived(batchId + "-evaluation"),
                FIXTURE_BOT,
                FIXTURE_PARTITION,
                officialEventFor(batchId),
                Instant.parse("2026-07-31T14:30:00Z"),
                List.of(OrderCandidate.heldSell(
                        candidateId, FIXTURE_INSTRUMENT, FIXTURE_FLOW, new BigDecimal("200"),
                        List.of("EXIT")))));

        ScopedCompositionResult result = composer.compose(batch);

        assertAll(
                () -> assertEquals(1, result.rejected()),
                () -> assertEquals(0, result.ordersComposed()),
                () -> assertEquals("NO_OPEN_POSITION", reasonOf(candidateId)));
    }

    /** Composing the same share-sized batch twice converges, as every other batch does. */
    @Test
    void aRedeliveredShareSizedBatchConverges() {
        UUID batchId = derived("share-redelivery");
        UUID candidateId = derived("share-redelivery-candidate");
        CandidateBatch batch = allocatedBuyBatch(batchId, candidateId, 1, 2, new BigDecimal("200"));

        ScopedCompositionResult first = composer.compose(batch);
        ScopedCompositionResult again = composer.compose(batch);

        assertAll(
                () -> assertEquals(first.intentBatchId(), again.intentBatchId()),
                () -> assertEquals(first.approved(), again.approved()),
                () -> assertEquals(1, count(
                        "select count(*) from trading.order_intents where batch_id = ?",
                        first.intentBatchId())),
                () -> assertEquals(1, count(
                        "select count(*) from trading.resource_reservations r "
                                + "join trading.order_intents i on i.id = r.intent_id "
                                + "where i.batch_id = ?", first.intentBatchId())));
    }

    private UUID composeAllocatedBuy(
            String seed, int numerator, int denominator, BigDecimal price) {
        UUID candidateId = derived(seed + "-candidate");
        composer.compose(allocatedBuyBatch(derived(seed), candidateId, numerator, denominator, price));
        return candidateId;
    }

    /**
     * A share-sized buy batch. The limit price doubles as the sizing mark, which is the composer's
     * documented rule: a candidate naming a price is sized from it rather than from the latest fill.
     */
    private CandidateBatch allocatedBuyBatch(
            UUID batchId, UUID candidateId, int numerator, int denominator, BigDecimal price) {
        return adapter.toDomain(new OrderCandidateBatch(
                OrderCandidateBatch.ALLOCATION_SCHEMA_VERSION,
                batchId,
                derived(batchId + "-evaluation"),
                FIXTURE_BOT,
                FIXTURE_PARTITION,
                officialEventFor(batchId),
                Instant.parse("2026-07-31T14:30:00Z"),
                List.of(OrderCandidate.allocatedBuy(
                        candidateId, FIXTURE_INSTRUMENT, FIXTURE_FLOW, numerator, denominator,
                        price, List.of("BASIC_RULE_MATCHED")))));
    }

    /**
     * The official bot event and evaluation run one batch needs, created once per batch.
     *
     * <p>Two canonical keys force this. {@code (bot_id, partition_id, source_event_id)} is the intent
     * batch's unique key — the partition of one official event is the trading isolation boundary — so
     * two batches sharing an event <em>are</em> the same batch and the store rightly refuses the
     * second. And {@code order_intents} carries a composite foreign key to {@code evaluation_runs},
     * so the evaluation a batch names has to exist as a row.
     *
     * <p>Both ids are derived from the batch id, so a redelivered batch reuses the same parents rather
     * than creating a second set and quietly becoming a different batch.
     */
    private UUID officialEventFor(UUID batchId) {
        UUID eventId = derived(batchId + "-bot-event");
        jdbc.sql("""
                        insert into bot.bot_events (id, bot_id, event_sequence, event_type,
                            event_schema_version, correlation_id, idempotency_key, occurred_at,
                            received_at, summary_document)
                        values (:id, :bot,
                            (select coalesce(max(event_sequence), 0) + 1 from bot.bot_events
                              where bot_id = :bot),
                            'ORDER_LIFECYCLE', 'v1', gen_random_uuid(), :key,
                            '2026-07-31T14:30:00+00', '2026-07-31T14:30:00+00', '{}')
                        on conflict (id) do nothing
                        """)
                .param("id", eventId)
                .param("bot", FIXTURE_BOT)
                .param("key", "rt4-197:" + eventId)
                .update();
        jdbc.sql("""
                        insert into bot.evaluation_runs (id, bot_id, partition_id, flow_id,
                            trigger_event_id, status, queued_at)
                        values (:id, :bot, :partition, :flow, :event, 'RUNNING',
                            '2026-07-31T14:30:00+00')
                        on conflict (id) do nothing
                        """)
                .param("id", derived(batchId + "-evaluation"))
                .param("bot", FIXTURE_BOT)
                .param("partition", FIXTURE_PARTITION)
                .param("flow", FIXTURE_FLOW)
                .param("event", eventId)
                .update();
        return eventId;
    }

    private static UUID derived(String seed) {
        return UUID.nameUUIDFromBytes(("rt4-197:" + seed).getBytes(StandardCharsets.UTF_8));
    }

    private String decisionOf(UUID candidateId) {
        return text("select decision::text from trading.order_intents where intent_key = ?",
                "candidate:" + candidateId);
    }

    private String reasonOf(UUID candidateId) {
        return text("select decision_reason_code from trading.order_intents where intent_key = ?",
                "candidate:" + candidateId);
    }

    private BigDecimal finalQuantityOf(UUID candidateId) {
        return new BigDecimal(text(
                "select final_quantity::text from trading.order_intents where intent_key = ?",
                "candidate:" + candidateId));
    }

    private BigDecimal requestedQuantityOf(UUID candidateId) {
        return new BigDecimal(text(
                "select requested_quantity::text from trading.order_intents where intent_key = ?",
                "candidate:" + candidateId));
    }

    private long count(String sql, Object argument) {
        return jdbc.sql(sql).param(argument).query(Long.class).single();
    }

    private String text(String sql, Object argument) {
        return jdbc.sql(sql).param(argument).query(String.class).single();
    }
}
