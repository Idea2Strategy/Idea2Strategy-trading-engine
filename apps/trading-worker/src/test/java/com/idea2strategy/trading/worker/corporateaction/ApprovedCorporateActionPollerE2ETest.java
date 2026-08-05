package com.idea2strategy.trading.worker.corporateaction;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.trading.application.corporateaction.CorporateActionService;
import com.idea2strategy.trading.application.port.CorporateActionStore;
import com.idea2strategy.trading.application.order.FillOrderCommand;
import com.idea2strategy.trading.domain.fill.FillAllocation;
import com.idea2strategy.trading.domain.fill.FillPosting;
import com.idea2strategy.trading.domain.fill.FillRecord;
import com.idea2strategy.trading.domain.order.OrderComponent;
import com.idea2strategy.trading.domain.order.OrderLifecycle;
import com.idea2strategy.trading.domain.order.OrderLifecycleFactory;
import com.idea2strategy.trading.domain.order.OrderPlacement;
import com.idea2strategy.trading.domain.order.OrderPolicyPins;
import com.idea2strategy.trading.domain.order.OrderScope;
import com.idea2strategy.trading.domain.order.OrderSide;
import com.idea2strategy.trading.domain.order.OrderTerms;
import com.idea2strategy.trading.domain.order.OrderType;
import com.idea2strategy.trading.domain.order.TimeInForce;
import com.idea2strategy.trading.domain.position.LotOpening;
import com.idea2strategy.trading.domain.position.LotSide;
import com.idea2strategy.trading.persistence.canonical.CanonicalBaseline;
import com.idea2strategy.trading.persistence.corporateaction.PostgresCorporateActionStore;
import com.idea2strategy.trading.persistence.event.PostgresBotEventStore;
import com.idea2strategy.trading.persistence.fill.PostgresFillRecordStore;
import com.idea2strategy.trading.persistence.order.PostgresOrderLifecycleStore;
import com.idea2strategy.trading.persistence.position.PostgresPositionLotStore;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * F92's initiation path against the real canonical schema: the market data owner's approved
 * corporate action, recorded exactly as the pipeline writes it (review state inside
 * {@code terms_document}), reaches every bot's official lots through one poll — and everything
 * that is not a proven, effective, unsuperseded approval is either left alone or refused durably.
 *
 * <p>Lots are built through the real order, fill and position write paths, as in
 * {@code CorporateActionPersistenceTest}: the deferred provenance triggers check that chain at
 * commit, so nothing here is seeded straight into a position table.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApprovedCorporateActionPollerE2ETest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    private static final UUID INSTRUMENT = UUID.fromString("f9200000-0000-4000-8000-000000000010");
    private static final UUID LONELY_INSTRUMENT =
            UUID.fromString("f9200000-0000-4000-8000-000000000011");
    private static final UUID MANIFEST = UUID.fromString("f9200000-0000-4000-8000-000000000020");
    private static final UUID LONELY_MANIFEST =
            UUID.fromString("f9200000-0000-4000-8000-000000000021");

    /** Approved, effective and confirmed: the one action the poller must apply. */
    private static final UUID APPLIED_SPLIT = UUID.fromString("f9200000-0000-4000-8000-000000000030");
    /** Researched but not decided: must stay inert. */
    private static final UUID PENDING_REVIEW = UUID.fromString("f9200000-0000-4000-8000-000000000031");
    /** Approved but effective in the future: not yet this poller's to apply. */
    private static final UUID FUTURE_SPLIT = UUID.fromString("f9200000-0000-4000-8000-000000000032");
    /** Approved content hash disagrees with the stored terms: refused durably. */
    private static final UUID STALE_HASH = UUID.fromString("f9200000-0000-4000-8000-000000000033");
    /** An approved type the execution engine has no arithmetic for: refused durably. */
    private static final UUID DIVIDEND = UUID.fromString("f9200000-0000-4000-8000-000000000034");
    /** Approved split on an instrument no bot holds: completes with zero bots. */
    private static final UUID LONELY_SPLIT = UUID.fromString("f9200000-0000-4000-8000-000000000035");
    /** Supersedes APPLIED_SPLIT after it already moved lots: refused durably. */
    private static final UUID LATE_SUPERSEDE = UUID.fromString("f9200000-0000-4000-8000-000000000036");
    /** A transient store failure must leave no receipt and succeed on the next poll. */
    private static final UUID RETRIED_SPLIT = UUID.fromString("f9200000-0000-4000-8000-000000000037");
    /** Two independent effective dates pin the canonical polling order. */
    private static final UUID EARLIER_ORDERED_SPLIT =
            UUID.fromString("f9200000-0000-4000-8000-000000000038");
    private static final UUID LATER_ORDERED_SPLIT =
            UUID.fromString("f9200000-0000-4000-8000-000000000039");
    /** Research can name a predecessor before review; it is not an official supersede yet. */
    private static final UUID UNAPPROVED_REVISION =
            UUID.fromString("f9200000-0000-4000-8000-000000000040");

    private static final Instant T0 = Instant.parse("2026-08-03T14:30:00Z");
    private static final Instant DECIDED_AT = Instant.parse("2026-08-04T09:00:00Z");
    private static final Instant EFFECTIVE_AT = Instant.parse("2026-08-05T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");
    private static final Instant FUTURE_EFFECTIVE = Instant.parse("2026-08-09T00:00:00Z");

    private static final int EVENT_POOL = 6;

    private static DriverManagerDataSource dataSource;
    private static JdbcClient jdbc;
    private static JdbcTransactionManager transactions;
    private static ApprovedCorporateActionPoller poller;
    private static BotContext firstBot;
    private static BotContext secondBot;

    @BeforeAll
    static void migrateAndSeed() throws Exception {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        CanonicalBaseline.migrateWithContributions(dataSource);
        jdbc = JdbcClient.create(dataSource);
        transactions = new JdbcTransactionManager(dataSource);
        poller = new ApprovedCorporateActionPoller(
                jdbc, new PostgresBotEventStore(jdbc, transactions),
                new CorporateActionService(new PostgresCorporateActionStore(jdbc, transactions)),
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), transactions);

        firstBot = new BotContext(1);
        secondBot = new BotContext(2);

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("set session_replication_role = replica");
            statement.addBatch("""
                    insert into identity.accounts (id, lifecycle_status, status_changed_at,
                        created_at)
                    values ('f9200000-0000-4000-8000-000000000001', 'ACTIVE',
                        '2026-08-01T00:00:00+00', '2026-08-01T00:00:00+00')
                    """);
            firstBot.seed(statement);
            secondBot.seed(statement);
            statement.addBatch("""
                    insert into market_data.instruments (id, asset_type, primary_exchange_mic,
                        currency_code)
                    values ('%s', 'STOCK', 'XNAS', 'USD'),
                           ('%s', 'STOCK', 'XNAS', 'USD')
                    """.formatted(INSTRUMENT, LONELY_INSTRUMENT));
            statement.addBatch(manifest(MANIFEST, INSTRUMENT));
            statement.addBatch(manifest(LONELY_MANIFEST, LONELY_INSTRUMENT));
            statement.addBatch(action(APPLIED_SPLIT, INSTRUMENT, MANIFEST, "applied-2-for-1",
                    EFFECTIVE_AT, 2, 1, null, approvedReview(APPLIED_SPLIT, termsHash(APPLIED_SPLIT))));
            statement.addBatch(action(PENDING_REVIEW, INSTRUMENT, MANIFEST, "pending",
                    EFFECTIVE_AT, 2, 1, null,
                    "\"review\":{\"state\":\"REVIEW_REQUIRED\"}"));
            statement.addBatch(action(FUTURE_SPLIT, INSTRUMENT, MANIFEST, "future-3-for-1",
                    FUTURE_EFFECTIVE, 3, 1, null,
                    approvedReview(FUTURE_SPLIT, termsHash(FUTURE_SPLIT))));
            statement.addBatch(action(STALE_HASH, INSTRUMENT, MANIFEST, "stale-hash",
                    EFFECTIVE_AT, 2, 1, null, approvedReview(STALE_HASH, "0".repeat(64))));
            statement.addBatch(action(UNAPPROVED_REVISION, INSTRUMENT, MANIFEST,
                    "unapproved-revision", EFFECTIVE_AT, 3, 1, APPLIED_SPLIT,
                    "\"review\":{\"state\":\"REVIEW_REQUIRED\"}"));
            statement.executeBatch();
            statement.execute("""
                    insert into market_data.corporate_actions (id, instrument_id,
                        source_manifest_id, provider_event_key, action_type, effective_at,
                        terms_document, terms_hash)
                    values ('%s', '%s', '%s', 'cash-dividend', 'DIVIDEND', '%s',
                        '{"actionType":"DIVIDEND","amount":"0.25",%s}', '%s')
                    """.formatted(DIVIDEND, INSTRUMENT, MANIFEST, EFFECTIVE_AT,
                    approvedReview(DIVIDEND, termsHash(DIVIDEND)), termsHash(DIVIDEND)));
            statement.execute(action(LONELY_SPLIT, LONELY_INSTRUMENT, LONELY_MANIFEST,
                    "lonely-2-for-1", EFFECTIVE_AT, 2, 1, null,
                    approvedReview(LONELY_SPLIT, termsHash(LONELY_SPLIT))));
            statement.execute("set session_replication_role = origin");
        }

        firstBot.preparePolicies();
        secondBot.preparePolicies();
        firstBot.openLot(1, "10.00000000", "100.00000000");
        secondBot.openLot(1, "4.00000000", "50.00000000");
        secondBot.openLot(2, "6.00000000", "55.00000000");
    }

    @Test
    @Order(1)
    void appliesTheApprovedSplitToEveryBotAndRefusesEverythingUnproven() {
        int applied = poller.pollOnce(10);

        // APPLIED_SPLIT reached both bots, LONELY_SPLIT completed with zero bots.
        assertEquals(2, applied);

        List<UUID> movementBots = jdbc.sql("""
                        select distinct bot_id from trading.lot_movements
                        where corporate_action_id = :actionId
                        """)
                .param("actionId", APPLIED_SPLIT).query(UUID.class).list();
        assertAll(
                () -> assertEquals(3, movementCount(APPLIED_SPLIT)),
                () -> assertTrue(movementBots.contains(firstBot.botId)),
                () -> assertTrue(movementBots.contains(secondBot.botId)),
                () -> assertDecimal("20.00000000", remainingQuantity(firstBot.botId)),
                () -> assertDecimal("20.00000000", remainingQuantity(secondBot.botId)),
                // A split re-denominates without moving money: the basis is carried unchanged.
                () -> assertDecimal("1002.00000000", costBasis(firstBot.botId)));

        assertAll(
                () -> assertEquals(1, facts(ApprovedCorporateActionPoller.APPLIED_TYPE, APPLIED_SPLIT)),
                () -> assertEquals(1, facts(ApprovedCorporateActionPoller.APPLIED_TYPE, LONELY_SPLIT)),
                () -> assertEquals(0, facts(ApprovedCorporateActionPoller.APPLIED_TYPE, PENDING_REVIEW)),
                () -> assertEquals(0,
                        facts(ApprovedCorporateActionPoller.APPLIED_TYPE, UNAPPROVED_REVISION)),
                () -> assertEquals(0, facts(ApprovedCorporateActionPoller.APPLIED_TYPE, FUTURE_SPLIT)),
                () -> assertEquals(1, facts(ApprovedCorporateActionPoller.REJECTED_TYPE, STALE_HASH)),
                () -> assertEquals(1, facts(ApprovedCorporateActionPoller.REJECTED_TYPE, DIVIDEND)),
                () -> assertEquals("STALE_CONTENT_HASH", rejectionReason(STALE_HASH)),
                () -> assertEquals("UNSUPPORTED_ACTION_TYPE", rejectionReason(DIVIDEND)),
                () -> assertEquals(0, movementCount(STALE_HASH)),
                () -> assertEquals(0, movementCount(DIVIDEND)),
                () -> assertEquals(0, movementCount(PENDING_REVIEW)),
                () -> assertEquals(0, movementCount(UNAPPROVED_REVISION)),
                () -> assertEquals(0, movementCount(FUTURE_SPLIT)));

        // The refusals also left the audit trail the card demands.
        assertEquals(2, jdbc.sql("""
                        select count(*) from operations.audit_events
                        where action_type = :actionType and target_domain = 'trading'
                        """)
                .param("actionType", ApprovedCorporateActionPoller.REJECTED_TYPE)
                .query(Integer.class).single());
    }

    @Test
    @Order(2)
    void convergesOnRedeliveryWithoutASecondSplit() {
        // A later poll has nothing pending: the durable facts are the receipt.
        assertEquals(0, poller.pollOnce(10));

        // Even if the receipt is lost, the replayed application converges on the recorded
        // movements instead of splitting a second time.
        jdbc.sql("delete from operations.outbox_messages where aggregate_id = :actionId")
                .param("actionId", APPLIED_SPLIT).update();
        assertEquals(1, poller.pollOnce(10));
        assertAll(
                () -> assertEquals(3, movementCount(APPLIED_SPLIT)),
                () -> assertDecimal("20.00000000", remainingQuantity(firstBot.botId)),
                () -> assertEquals(1,
                        facts(ApprovedCorporateActionPoller.APPLIED_TYPE, APPLIED_SPLIT)));
    }

    @Test
    @Order(3)
    void refusesASupersedeArrivingAfterTheApplication() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("set session_replication_role = replica");
            statement.execute(action(LATE_SUPERSEDE, INSTRUMENT, MANIFEST, "late-3-for-1",
                    EFFECTIVE_AT, 3, 1, APPLIED_SPLIT,
                    approvedReview(LATE_SUPERSEDE, termsHash(LATE_SUPERSEDE))));
            statement.execute("set session_replication_role = origin");
        }

        assertEquals(0, poller.pollOnce(10));
        assertAll(
                () -> assertEquals(1,
                        facts(ApprovedCorporateActionPoller.REJECTED_TYPE, LATE_SUPERSEDE)),
                () -> assertEquals("SUPERSEDE_AFTER_APPLICATION", rejectionReason(LATE_SUPERSEDE)),
                () -> assertEquals(0, movementCount(LATE_SUPERSEDE)),
                // The applied movements stand: refusal never reverses official history.
                () -> assertEquals(3, movementCount(APPLIED_SPLIT)));
    }

    @Test
    @Order(4)
    void retriesATransientFailureWithoutLeavingPartialBusinessState() throws Exception {
        insertAction(RETRIED_SPLIT, INSTRUMENT, MANIFEST, "retry-2-for-1",
                EFFECTIVE_AT.plusSeconds(1), 2, 1, null,
                approvedReview(RETRIED_SPLIT, termsHash(RETRIED_SPLIT)));

        CorporateActionStore canonical = new PostgresCorporateActionStore(jdbc, transactions);
        AtomicInteger attempts = new AtomicInteger();
        CorporateActionStore failsOnce = application -> {
            if (application.action().actionId().equals(RETRIED_SPLIT)
                    && attempts.getAndIncrement() == 0) {
                throw new IllegalStateException("temporary database interruption");
            }
            return canonical.apply(application);
        };
        ApprovedCorporateActionPoller retrying = poller(new CorporateActionService(failsOnce));

        assertThrows(IllegalStateException.class, () -> retrying.pollOnce(1));
        assertAll(
                () -> assertEquals(0, movementCount(RETRIED_SPLIT)),
                () -> assertEquals(0,
                        facts(ApprovedCorporateActionPoller.APPLIED_TYPE, RETRIED_SPLIT)),
                () -> assertEquals(0, botEvents(RETRIED_SPLIT)));

        assertEquals(1, retrying.pollOnce(1));
        assertAll(
                () -> assertEquals(3, movementCount(RETRIED_SPLIT)),
                () -> assertEquals(1,
                        facts(ApprovedCorporateActionPoller.APPLIED_TYPE, RETRIED_SPLIT)),
                () -> assertEquals(2, botEvents(RETRIED_SPLIT)));
    }

    @Test
    @Order(5)
    void processesDurableInputByEffectiveTimeThenStableIdentity() throws Exception {
        insertAction(LATER_ORDERED_SPLIT, LONELY_INSTRUMENT, LONELY_MANIFEST,
                "ordered-later", EFFECTIVE_AT.plusSeconds(3), 1, 1, null,
                approvedReview(LATER_ORDERED_SPLIT, termsHash(LATER_ORDERED_SPLIT)));
        insertAction(EARLIER_ORDERED_SPLIT, LONELY_INSTRUMENT, LONELY_MANIFEST,
                "ordered-earlier", EFFECTIVE_AT.plusSeconds(2), 1, 1, null,
                approvedReview(EARLIER_ORDERED_SPLIT, termsHash(EARLIER_ORDERED_SPLIT)));

        assertEquals(1, poller.pollOnce(1));
        assertAll(
                () -> assertEquals(1,
                        facts(ApprovedCorporateActionPoller.APPLIED_TYPE, EARLIER_ORDERED_SPLIT)),
                () -> assertEquals(0,
                        facts(ApprovedCorporateActionPoller.APPLIED_TYPE, LATER_ORDERED_SPLIT)));

        assertEquals(1, poller.pollOnce(1));
        assertEquals(1, facts(ApprovedCorporateActionPoller.APPLIED_TYPE, LATER_ORDERED_SPLIT));
    }

    private static ApprovedCorporateActionPoller poller(CorporateActionService service) {
        return new ApprovedCorporateActionPoller(
                jdbc, new PostgresBotEventStore(jdbc, transactions), service,
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), transactions);
    }

    private static void insertAction(UUID id, UUID instrumentId, UUID manifestId,
            String providerEventKey, Instant effectiveAt, long to, long from, UUID supersedes,
            String review) throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(action(id, instrumentId, manifestId, providerEventKey,
                    effectiveAt, to, from, supersedes, review));
        }
    }

    private static int botEvents(UUID actionId) {
        return jdbc.sql("""
                        select count(*) from bot.bot_events
                        where event_type = 'CORPORATE_ACTION_APPLIED'
                          and idempotency_key = 'CORPORATE_ACTION_APPLIED:' || :actionId
                        """)
                .param("actionId", actionId.toString()).query(Integer.class).single();
    }

    private static int movementCount(UUID actionId) {
        return jdbc.sql(
                        "select count(*) from trading.lot_movements where corporate_action_id = :id")
                .param("id", actionId).query(Integer.class).single();
    }

    private static int facts(String eventType, UUID actionId) {
        return jdbc.sql("""
                        select count(*) from operations.outbox_messages
                        where owner_domain = 'trading' and event_type = :eventType
                          and aggregate_id = :actionId
                        """)
                .param("eventType", eventType).param("actionId", actionId)
                .query(Integer.class).single();
    }

    private static String rejectionReason(UUID actionId) {
        return jdbc.sql("""
                        select payload_document ->> 'reasonCode' from operations.outbox_messages
                        where owner_domain = 'trading' and event_type = :eventType
                          and aggregate_id = :actionId
                        """)
                .param("eventType", ApprovedCorporateActionPoller.REJECTED_TYPE)
                .param("actionId", actionId).query(String.class).single();
    }

    private static BigDecimal remainingQuantity(UUID botId) {
        return jdbc.sql("""
                        select sum(projection.remaining_quantity)
                        from trading.position_lot_projections projection
                        join trading.position_lots lot on lot.id = projection.position_lot_id
                        where lot.bot_id = :botId and lot.instrument_id = :instrumentId
                        """)
                .param("botId", botId).param("instrumentId", INSTRUMENT)
                .query(BigDecimal.class).single();
    }

    private static BigDecimal costBasis(UUID botId) {
        return jdbc.sql("""
                        select sum(projection.remaining_cost_basis_amount)
                        from trading.position_lot_projections projection
                        join trading.position_lots lot on lot.id = projection.position_lot_id
                        where lot.bot_id = :botId and lot.instrument_id = :instrumentId
                        """)
                .param("botId", botId).param("instrumentId", INSTRUMENT)
                .query(BigDecimal.class).single();
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "expected " + expected + " but was " + actual);
    }

    private static String termsHash(UUID actionId) {
        String hex = actionId.toString().replace("-", "");
        return (hex + hex).substring(0, 64);
    }

    private static String manifest(UUID id, UUID instrumentId) {
        return """
                insert into market_data.dataset_manifests (id, feed_id, instrument_id, data_layer,
                    resolution, revision_number, status, period_start, period_end, schema_version,
                    dataset_hash, available_at)
                values ('%s', gen_random_uuid(), '%s', 'NORMALIZED', 'EVENT', 1, 'AVAILABLE',
                    '2026-08-01T00:00:00+00', '2026-08-03T00:00:00+00', 'v1', '%s',
                    '2026-08-01T00:00:00+00')
                """.formatted(id, instrumentId, termsHash(id));
    }

    /** The review record exactly as the pipeline's verified transition writes it. */
    private static String approvedReview(UUID actionId, String decidedContentHash) {
        return """
                "review":{"state":"APPROVED","decision":"APPROVE",\
                "candidate_id":"%s","decided_content_hash":"%s",\
                "evidence_bindings":["%s"],\
                "actor_id":"f9200000-0000-4000-8000-000000000002",\
                "audit_id":"f9200000-0000-4000-8000-000000000003",\
                "permission_id":"f9200000-0000-4000-8000-000000000004",\
                "request_schema_version":"corporate-action-approval.v1",\
                "decided_at":"%s","delivery_id":"%s",\
                "envelope_hash":"%s","aggregate_sequence":1,\
                "supersedes_candidate_id":null,"rationale":"verified"}"""
                .formatted(actionId, decidedContentHash, termsHash(actionId),
                        DECIDED_AT, actionId, termsHash(actionId));
    }

    private static String action(UUID id, UUID instrumentId, UUID manifestId,
            String providerEventKey, Instant effectiveAt, long to, long from, UUID supersedes,
            String review) {
        return """
                insert into market_data.corporate_actions (id, instrument_id, source_manifest_id,
                    provider_event_key, action_type, effective_at, terms_document, terms_hash,
                    supersedes_action_id)
                values ('%s', '%s', '%s', '%s', 'SPLIT', '%s',
                    '{"actionType":"SPLIT","ratio":{"from":%d,"to":%d},%s}', '%s', %s)
                """.formatted(id, instrumentId, manifestId, providerEventKey, effectiveAt, from,
                to, review, termsHash(id), supersedes == null ? "null" : "'" + supersedes + "'");
    }

    /**
     * One bot with its partition, flow, seeded official event pool and finalized intent batch,
     * plus the real order, fill and position write path to open canonical lots.
     */
    private static final class BotContext {

        private final UUID botId;
        private final UUID partitionId;
        private final UUID flowId;
        private final UUID evaluationId;
        private final UUID intentBatchId;
        private final UUID feePolicyId;
        private final int index;

        private PostgresPositionLotStore lots;
        private PostgresOrderLifecycleStore orders;
        private PostgresFillRecordStore fills;

        private BotContext(int index) {
            this.index = index;
            this.botId = id("1");
            this.partitionId = id("2");
            this.flowId = id("3");
            this.evaluationId = id("4");
            this.intentBatchId = id("5");
            this.feePolicyId = id("6");
        }

        private UUID id(String kind) {
            return UUID.fromString("f92%s0000-0000-4000-8000-%012d".formatted(kind, index));
        }

        private UUID event(int slot) {
            return UUID.fromString("f92e0000-0000-4000-8000-%04d%08d".formatted(index, slot));
        }

        private void seed(Statement statement) throws Exception {
            statement.addBatch("""
                    insert into bot.bots (id, owner_account_id, mode, name, lifecycle_status,
                        lifecycle_changed_at, created_at, execution_eligible_from)
                    values ('%s', 'f9200000-0000-4000-8000-000000000001', 'BASIC', 'F92 bot %d',
                        'RUNNING', '2026-08-01T00:00:00+00', '2026-08-01T00:00:00+00',
                        '2026-08-01T00:00:00+00')
                    """.formatted(botId, index));
            statement.addBatch("""
                    insert into bot.bot_partitions (id, bot_id, name, budget_cap_bps,
                        position_x, position_y, configuration_hash)
                    values ('%s', '%s', 'Partition', 5000, 0, 0, '%s')
                    """.formatted(partitionId, botId, "c".repeat(64)));
            statement.addBatch("""
                    insert into bot.flows (id, partition_id, name, element_catalog_version_id,
                        compiled_flow_plan_id, position_x, position_y, semantic_document,
                        layout_document, layout_schema_version, semantic_hash, layout_hash,
                        configuration_hash)
                    values ('%s', '%s', 'Flow', gen_random_uuid(), gen_random_uuid(), 0, 0,
                        '{}', '{}', 'v1', '%s', '%s', '%s')
                    """.formatted(flowId, partitionId, "a".repeat(64), "b".repeat(64),
                    "c".repeat(64)));
            for (int slot = 1; slot <= EVENT_POOL; slot++) {
                statement.addBatch("""
                        insert into bot.bot_events (id, bot_id, event_sequence, event_type,
                            event_schema_version, correlation_id, idempotency_key, occurred_at,
                            received_at, summary_document)
                        values ('%s', '%s', %d, 'ORDER_ACCEPTED', 'v1', gen_random_uuid(),
                            'f92-seed-%d', '2026-08-01T00:00:00+00', '2026-08-01T00:00:00+00',
                            '{}')
                        """.formatted(event(slot), botId, slot, slot));
            }
            statement.addBatch("""
                    insert into bot.evaluation_runs (id, bot_id, partition_id, flow_id,
                        trigger_event_id, status, queued_at)
                    values ('%s', '%s', '%s', '%s', '%s', 'RUNNING', '2026-08-01T00:00:00+00')
                    """.formatted(evaluationId, botId, partitionId, flowId, event(1)));
        }

        private void preparePolicies() {
            lots = new PostgresPositionLotStore(jdbc, transactions);
            orders = new PostgresOrderLifecycleStore(jdbc, transactions);
            fills = new PostgresFillRecordStore(jdbc, transactions);
            jdbc.sql("""
                            insert into trading.fee_policy_versions (id, policy_code, version,
                                fee_rate_bps, calculation_rules_version, rules_hash,
                                effective_from, published_at)
                            values (:id, :code, 'v1', 20, 'fee-calc:v1', :hash,
                                '2026-01-01T00:00:00+00', '2026-01-01T00:00:00+00')
                            """)
                    .param("id", feePolicyId).param("code", "OFFICIAL_FEE_F92_" + index)
                    .param("hash", ("f" + index).repeat(32)).update();
            jdbc.sql("""
                            insert into trading.order_intent_batches (id, bot_id, partition_id,
                                source_event_id, status, conflict_policy_hash,
                                composition_rules_version, input_state_hash, result_hash,
                                finalized_at)
                            values (:id, :bot, :partition, :event, 'FINALIZED', :hash,
                                'order-intent-composition:v1', :hash, :hash,
                                '2026-08-01T00:00:00+00')
                            """)
                    .param("id", intentBatchId).param("bot", botId).param("partition", partitionId)
                    .param("event", event(1)).param("hash", "e".repeat(64)).update();
        }

        /** Opens one real canonical lot through the order, fill and position write paths. */
        private void openLot(int lotIndex, String quantity, String price) {
            UUID intentId = UUID.fromString(
                    "f92f0000-0000-4000-8000-%04d%08d".formatted(index, lotIndex));
            UUID candidateId = UUID.fromString(
                    "f92f0000-0000-4000-8000-%04d1%07d".formatted(index, lotIndex));
            UUID acceptedEventId = event(lotIndex * 2 - 1);
            UUID botEventId = event(lotIndex * 2);
            BigDecimal size = new BigDecimal(quantity);
            BigDecimal unitPrice = new BigDecimal(price);
            BigDecimal gross = size.multiply(unitPrice).setScale(8);
            BigDecimal fee = gross.multiply(new BigDecimal("0.002")).setScale(8);
            BigDecimal cash = gross.add(fee).negate();
            Instant occurredAt = T0.plusSeconds(lotIndex);
            OrderScope scope = new OrderScope(botId, partitionId);
            OrderPolicyPins pins = new OrderPolicyPins(feePolicyId, "broker-rules:v1",
                    "precision-rules:v1", "order-intent-composition:v1");

            jdbc.sql("""
                            insert into trading.order_intents (id, bot_id, batch_id,
                                source_event_id, origin_type, evaluation_run_id, partition_id,
                                flow_id, instrument_id, intent_key, side, position_effect,
                                order_type, time_in_force, requested_quantity,
                                post_netting_quantity, final_quantity, decision,
                                decision_reason_code)
                            values (:id, :bot, :batch, :event, 'FLOW_EVALUATION', :evaluation,
                                :partition, :flow, :instrument, :key, 'BUY', 'OPEN_LONG',
                                'MARKET', 'DAY', :quantity, :quantity, :quantity, 'APPROVED',
                                'ELIGIBLE')
                            """)
                    .param("id", intentId).param("bot", botId).param("batch", intentBatchId)
                    .param("event", event(1)).param("evaluation", evaluationId)
                    .param("partition", partitionId).param("flow", flowId)
                    .param("instrument", INSTRUMENT).param("key", "candidate:" + candidateId)
                    .param("quantity", size).update();

            OrderLifecycle lifecycle = new OrderLifecycleFactory().accepted(new OrderTerms(
                    intentId, candidateId, INSTRUMENT, OrderSide.BUY, size, OrderType.MARKET,
                    TimeInForce.DAY, null, null, null, null), T0);
            orders.createOrLoad(new OrderPlacement(
                    lifecycle, scope, pins, acceptedEventId,
                    List.of(new OrderComponent(intentId, size, 1))));
            UUID orderId = lifecycle.orderId();
            UUID componentId = jdbc.sql(
                            "select id from trading.order_components where order_id = :orderId")
                    .param("orderId", orderId).query(UUID.class).single();

            FillRecord record = FillRecord.original(
                    orderId, "f92-execution-" + index + "-" + lotIndex, size, unitPrice, fee,
                    new BigDecimal("0.01"), occurredAt, occurredAt);
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                fills.appendOrLoad(new FillPosting(
                        record, scope, botEventId, feePolicyId, 20, "precision-rules:v1",
                        unitPrice, occurredAt, "m".repeat(64), gross, gross, cash,
                        "fill-allocation:v1",
                        List.of(new FillAllocation(componentId, 1, size, gross, fee, cash))));
                orders.apply(new FillOrderCommand(
                        UUID.randomUUID(), orderId, botEventId, 1, size, occurredAt));
            });
            UUID allocationId = jdbc.sql(
                            "select id from trading.fill_component_allocations where fill_id = :fillId")
                    .param("fillId", record.fillRecordId()).query(UUID.class).single();
            lots.open(new LotOpening(
                    scope, flowId, INSTRUMENT, componentId, allocationId, botEventId,
                    LotSide.LONG, size, gross, fee, occurredAt));
        }
    }
}
