package com.idea2strategy.trading.persistence.corporateaction;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idea2strategy.trading.application.corporateaction.CorporateActionApplicationResult;
import com.idea2strategy.trading.application.corporateaction.CorporateActionConflictException;
import com.idea2strategy.trading.application.order.FillOrderCommand;
import com.idea2strategy.trading.domain.corporateaction.ApprovedCorporateAction;
import com.idea2strategy.trading.domain.corporateaction.CorporateActionApplication;
import com.idea2strategy.trading.domain.corporateaction.CorporateActionApprovalStatus;
import com.idea2strategy.trading.domain.corporateaction.CorporateActionType;
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
import com.idea2strategy.trading.persistence.fill.PostgresFillRecordStore;
import com.idea2strategy.trading.persistence.order.PostgresOrderLifecycleStore;
import com.idea2strategy.trading.persistence.position.JooqPositionLotQuery;
import com.idea2strategy.trading.persistence.position.PostgresPositionLotStore;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

/**
 * Proves the corporate-action write path against the real canonical schema.
 *
 * <p>Nothing here is seeded straight into a position table. A canonical lot exists only because an
 * approved intent became an order, a fill and an allocation, and the deferred provenance triggers
 * check that whole chain at commit, so each test builds it through the order, fill and position
 * write paths and then applies the action on top.
 *
 * <p>The action itself is seeded into {@code market_data}, not into {@code trading}: it belongs to
 * the market data owner, and the point of the migration is that this service reads it as evidence
 * rather than restating it in a private table of its own.
 */
@Testcontainers(disabledWithoutDocker = true)
class CorporateActionPersistenceTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    private static final UUID BOT = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID PARTITION = UUID.fromString("31000000-0000-4000-8000-000000000001");
    private static final UUID FLOW = UUID.fromString("32000000-0000-4000-8000-000000000001");
    private static final UUID EVALUATION = UUID.fromString("34000000-0000-4000-8000-000000000001");
    private static final UUID INSTRUMENT = UUID.fromString("36000000-0000-4000-8000-000000000001");
    private static final UUID FEE_POLICY = UUID.fromString("37000000-0000-4000-8000-000000000001");
    private static final UUID INTENT_BATCH = UUID.fromString("38000000-0000-4000-8000-000000000001");
    private static final UUID MANIFEST = UUID.fromString("3b000000-0000-4000-8000-000000000001");
    private static final UUID QUARANTINED_MANIFEST =
            UUID.fromString("3b000000-0000-4000-8000-000000000002");

    /** Confirmed 1-for-2 split: one share before becomes two after. */
    private static final UUID SPLIT = UUID.fromString("3c000000-0000-4000-8000-000000000001");
    /** Confirmed 3-for-1 reverse split, which no canonical quantity column can hold exactly. */
    private static final UUID UNREPRESENTABLE = UUID.fromString("3c000000-0000-4000-8000-000000000002");
    /** Published from a quarantined dataset, so the data owner has not confirmed it. */
    private static final UUID QUARANTINED = UUID.fromString("3c000000-0000-4000-8000-000000000003");
    /** Superseded by a later revision of the same event. */
    private static final UUID SUPERSEDED = UUID.fromString("3c000000-0000-4000-8000-000000000004");
    private static final UUID SUPERSEDING = UUID.fromString("3c000000-0000-4000-8000-000000000005");

    private static final Instant T0 = Instant.parse("2026-08-02T14:30:00Z");
    private static final Instant EFFECTIVE_AT = T0.plusSeconds(600);

    private static final OrderScope SCOPE = new OrderScope(BOT, PARTITION);
    private static final OrderPolicyPins PINS = new OrderPolicyPins(
            FEE_POLICY, "broker-rules:v1", "precision-rules:v1", "order-intent-composition:v1");

    private static final int EVENT_POOL = 24;
    private static final List<UUID> EVENTS = new ArrayList<>();

    private static DriverManagerDataSource dataSource;
    private static JdbcClient jdbc;
    private static JdbcTransactionManager transactions;
    private static PostgresCorporateActionStore store;
    private static JooqCorporateActionQuery query;
    private static JooqPositionLotQuery positions;
    private static PostgresPositionLotStore lots;
    private static PostgresOrderLifecycleStore orders;
    private static PostgresFillRecordStore fills;

    @BeforeAll
    static void migrateAndSeed() throws Exception {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        CanonicalBaseline.migrateWithContributions(dataSource);
        jdbc = JdbcClient.create(dataSource);
        transactions = new JdbcTransactionManager(dataSource);
        store = newStore();
        query = new JooqCorporateActionQuery(DSL.using(dataSource, SQLDialect.POSTGRES));
        positions = new JooqPositionLotQuery(DSL.using(dataSource, SQLDialect.POSTGRES));
        lots = new PostgresPositionLotStore(jdbc, transactions);
        orders = new PostgresOrderLifecycleStore(jdbc, transactions);
        fills = new PostgresFillRecordStore(jdbc, transactions);

        for (int index = 1; index <= EVENT_POOL; index++) {
            EVENTS.add(UUID.fromString("33000000-0000-4000-8000-%012d".formatted(index)));
        }

        // bot.* and market_data.* belong to other services; seeded with referential triggers off
        // exactly as the canonical contract fixtures do. The writes under test run with them back on.
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("set session_replication_role = replica");
            statement.addBatch("""
                    insert into identity.accounts (id, lifecycle_status, status_changed_at,
                        created_at)
                    values ('a0000000-0000-4000-8000-000000000003', 'ACTIVE',
                        '2026-08-01T00:00:00+00', '2026-08-01T00:00:00+00')
                    """);
            statement.addBatch("""
                    insert into bot.bots (id, owner_account_id, mode, name, lifecycle_status,
                        lifecycle_changed_at, created_at, execution_eligible_from)
                    values ('%s', 'a0000000-0000-4000-8000-000000000003', 'BASIC', 'Corporate bot',
                        'RUNNING', '2026-08-01T00:00:00+00', '2026-08-01T00:00:00+00',
                        '2026-08-01T00:00:00+00')
                    """.formatted(BOT));
            statement.addBatch("""
                    insert into bot.bot_partitions (id, bot_id, name, budget_cap_bps,
                        position_x, position_y, configuration_hash)
                    values ('%s', '%s', 'Partition', 5000, 0, 0, '%s')
                    """.formatted(PARTITION, BOT, "c".repeat(64)));
            statement.addBatch("""
                    insert into bot.flows (id, partition_id, name, element_catalog_version_id,
                        compiled_flow_plan_id, position_x, position_y, semantic_document,
                        layout_document, layout_schema_version, semantic_hash, layout_hash,
                        configuration_hash)
                    values ('%s', '%s', 'Flow', gen_random_uuid(), gen_random_uuid(), 0, 0,
                        '{}', '{}', 'v1', '%s', '%s', '%s')
                    """.formatted(FLOW, PARTITION, "a".repeat(64), "b".repeat(64), "c".repeat(64)));
            for (int index = 0; index < EVENT_POOL; index++) {
                statement.addBatch("""
                        insert into bot.bot_events (id, bot_id, event_sequence, event_type,
                            event_schema_version, correlation_id, idempotency_key, occurred_at,
                            received_at, summary_document)
                        values ('%s', '%s', %d, 'CORPORATE_ACTION', 'v1', gen_random_uuid(),
                            'corporate-seed-%d', '2026-08-01T00:00:00+00', '2026-08-01T00:00:00+00',
                            '{}')
                        """.formatted(EVENTS.get(index), BOT, index + 1, index));
            }
            statement.addBatch("""
                    insert into bot.evaluation_runs (id, bot_id, partition_id, flow_id,
                        trigger_event_id, status, queued_at)
                    values ('%s', '%s', '%s', '%s', '%s', 'RUNNING', '2026-08-01T00:00:00+00')
                    """.formatted(EVALUATION, BOT, PARTITION, FLOW, EVENTS.getFirst()));
            statement.addBatch("""
                    insert into market_data.instruments (id, asset_type, primary_exchange_mic,
                        currency_code)
                    values ('%s', 'STOCK', 'XNAS', 'USD')
                    """.formatted(INSTRUMENT));
            statement.addBatch(manifest(MANIFEST, "AVAILABLE", 1));
            statement.addBatch(manifest(QUARANTINED_MANIFEST, "QUARANTINED", 2));
            statement.addBatch(corporateAction(SPLIT, MANIFEST, "split-2-for-1", 2, 1, null));
            statement.addBatch(
                    corporateAction(UNREPRESENTABLE, MANIFEST, "reverse-1-for-3", 1, 3, null));
            statement.addBatch(
                    corporateAction(QUARANTINED, QUARANTINED_MANIFEST, "unconfirmed", 2, 1, null));
            statement.addBatch(corporateAction(SUPERSEDED, MANIFEST, "revision-1", 2, 1, null));
            statement.addBatch(
                    corporateAction(SUPERSEDING, MANIFEST, "revision-2", 3, 1, SUPERSEDED));
            statement.executeBatch();
            statement.execute("set session_replication_role = origin");
        }

        jdbc.sql("""
                insert into trading.fee_policy_versions (id, policy_code, version, fee_rate_bps,
                    calculation_rules_version, rules_hash, effective_from, published_at)
                values (:id, 'OFFICIAL_FEE', 'v1', 20, 'fee-calc:v1', :hash,
                    '2026-01-01T00:00:00+00', '2026-01-01T00:00:00+00')
                """).param("id", FEE_POLICY).param("hash", "f".repeat(64)).update();
        jdbc.sql("""
                insert into trading.order_intent_batches (id, bot_id, partition_id, source_event_id,
                    status, conflict_policy_hash, composition_rules_version, input_state_hash,
                    result_hash, finalized_at)
                values (:id, :bot, :partition, :event, 'FINALIZED', :hash,
                    'order-intent-composition:v1', :hash, :hash, '2026-08-01T00:00:00+00')
                """).param("id", INTENT_BATCH).param("bot", BOT).param("partition", PARTITION)
                .param("event", EVENTS.getFirst()).param("hash", "e".repeat(64)).update();
    }

    /**
     * Removed in one transaction with the checks deferred. The canonical consistency triggers fire
     * per statement otherwise, and a half-cleared position legitimately fails them on the way out.
     */
    @BeforeEach
    void clearTradingRows() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("set constraints all deferred");
            statement.addBatch("delete from trading.partition_position_projections");
            statement.addBatch("delete from trading.flow_position_projections");
            statement.addBatch("delete from trading.position_lot_projections");
            statement.addBatch("delete from trading.lot_movements");
            statement.addBatch("delete from trading.position_lots");
            statement.addBatch("delete from trading.fill_component_allocations");
            statement.addBatch("delete from trading.fills");
            statement.addBatch("delete from trading.order_state_projections");
            statement.addBatch("delete from trading.order_events");
            statement.addBatch("delete from trading.order_components");
            statement.addBatch("delete from trading.orders");
            statement.addBatch("delete from trading.order_intents");
            statement.executeBatch();
            connection.commit();
        }
    }

    /**
     * Canonical records the split as history against every lot, not as an overwrite: the opening
     * evidence stays exactly as it was and the projections move to a value the movement explains.
     */
    @Test
    void anApprovedSplitAppendsOneAdjustmentMovementPerLotAndPreservesEveryBasis() {
        openLot(1, "1", "100", 1);
        openLot(2, "2", "110", 2);

        CorporateActionApplicationResult result = store.apply(application(SPLIT, 2, 1, 21));

        var adjustments = query.adjustments(SPLIT);
        var storedLots = positions.lots(BOT, PARTITION, FLOW, INSTRUMENT);
        var flow = positions.flow(FLOW, INSTRUMENT).orElseThrow();
        var partition = positions.partition(PARTITION, INSTRUMENT).orElseThrow();

        assertAll(
                () -> assertEquals(2, result.adjustedLots()),
                () -> assertEquals(1, result.adjustedFlowPositions()),
                () -> assertEquals(BOT, result.botId()),
                () -> assertEquals(PostgresCorporateActionStore.NO_MONETARY_POSTING,
                        result.ledgerEffect()),
                () -> assertEquals(2, adjustments.size()),
                () -> assertTrue(adjustments.stream().allMatch(
                                movement -> "CORPORATE_ACTION_ADJUSTMENT".equals(
                                        movement.movementType())),
                        "the split is a corporate-action movement, not a trade"),
                () -> assertTrue(adjustments.stream().allMatch(
                                movement -> EVENTS.get(21).equals(movement.botEventId())),
                        "every canonical movement names the official event that caused it"),
                () -> assertEquals(EFFECTIVE_AT, adjustments.getFirst().occurredAt()),
                () -> assertDecimal("1", adjustments.getFirst().quantityDelta()),
                () -> assertDecimal("2", adjustments.get(1).quantityDelta()),
                () -> assertTrue(adjustments.stream().allMatch(
                                movement -> movement.costBasisDelta().signum() == 0),
                        "a split moves no cost"),
                () -> assertDecimal("2", adjustments.getFirst().remainingAfter()),
                () -> assertDecimal("100.20", adjustments.getFirst().costBasisAfter()),
                () -> assertDecimal("4", adjustments.get(1).remainingAfter()),
                () -> assertDecimal("220.44", adjustments.get(1).costBasisAfter()),
                // position_lots is immutable evidence of the opening and is deliberately not rewritten.
                () -> assertDecimal("1", storedLots.getFirst().openedQuantity()),
                () -> assertDecimal("100.20", storedLots.getFirst().unitCost()),
                () -> assertDecimal("100.20", storedLots.getFirst().openedCostBasisAmount()),
                () -> assertDecimal("2", storedLots.getFirst().remainingQuantity()),
                () -> assertDecimal("100.20", storedLots.getFirst().remainingCostBasisAmount()),
                () -> assertEquals(adjustments.getFirst().movementId(),
                        storedLots.getFirst().lastMovementId(),
                        "the projection points at the movement that produced it"),
                () -> assertDecimal("4", storedLots.get(1).remainingQuantity()),
                () -> assertDecimal("220.44", storedLots.get(1).remainingCostBasisAmount()),
                () -> assertDecimal("6", flow.longQuantity()),
                () -> assertDecimal("320.64", flow.costBasisAmount()),
                () -> assertEquals(64, flow.projectionHash().length()),
                () -> assertDecimal("6", partition.netQuantity()),
                // The basis is unchanged, so only the per-unit figure halves.
                () -> assertDecimal("53.44", partition.averageCost().orElseThrow()),
                () -> assertDecimal("0", partition.realizedPnl()),
                () -> assertEquals(0, jdbc.sql("select count(*) from trading.ledger_transactions")
                        .query(Integer.class).single(), "a split posts nothing to the ledger"));
    }

    /**
     * Canonical makes {@code (position_lot_id, bot_event_id)} unique and the movement ids are
     * derived from the action, the lot and the event, so a redelivery lands on the rows it wrote.
     */
    @Test
    void redeliveringTheSameApplicationWritesNoSecondMovement() {
        openLot(1, "1", "100", 1);
        CorporateActionApplication application = application(SPLIT, 2, 1, 21);

        CorporateActionApplicationResult first = store.apply(application);

        assertAll(
                () -> assertEquals(first, newStore().apply(application)),
                () -> assertEquals(1, query.adjustments(SPLIT).size()),
                () -> assertDecimal("2",
                        positions.flow(FLOW, INSTRUMENT).orElseThrow().longQuantity()));
    }

    /**
     * The confirmed action is immutable evidence the market data owner wrote, so terms that
     * disagree with it are refused instead of overwriting the ones already recorded. A second
     * official event claiming the same action is refused too: canonical would otherwise apply the
     * same split twice.
     */
    @Test
    void termsOrAnEventThatDisagreeWithTheConfirmedActionAreRefused() {
        openLot(1, "1", "100", 1);
        store.apply(application(SPLIT, 2, 1, 21));

        CorporateActionConflictException wrongRatio = assertThrows(
                CorporateActionConflictException.class,
                () -> store.apply(application(SPLIT, 3, 1, 22)));
        CorporateActionConflictException secondEvent = assertThrows(
                CorporateActionConflictException.class,
                () -> store.apply(application(SPLIT, 2, 1, 22)));

        assertAll(
                () -> assertTrue(wrongRatio.getMessage().contains("numerator"),
                        wrongRatio::getMessage),
                () -> assertTrue(secondEvent.getMessage().contains("another official event"),
                        secondEvent::getMessage),
                () -> assertEquals(1, query.adjustments(SPLIT).size()),
                () -> assertDecimal("2",
                        positions.flow(FLOW, INSTRUMENT).orElseThrow().longQuantity()));
    }

    /**
     * The private schema kept eighteen decimals and would have stored a third of a share. Canonical
     * keeps eight, so the whole application rolls back rather than rounding one lot into a position
     * that no longer reconciles with its movements.
     */
    @Test
    void aRatioCanonicalCannotHoldRollsBackWithoutTouchingThePosition() {
        openLot(1, "1", "100", 1);
        openLot(2, "2", "110", 2);

        assertThrows(ArithmeticException.class,
                () -> store.apply(application(UNREPRESENTABLE, 1, 3, 21)));

        var storedLots = positions.lots(BOT, PARTITION, FLOW, INSTRUMENT);
        assertAll(
                () -> assertEquals(0, query.adjustments(UNREPRESENTABLE).size()),
                () -> assertDecimal("1", storedLots.getFirst().remainingQuantity()),
                () -> assertDecimal("2", storedLots.get(1).remainingQuantity()),
                () -> assertDecimal("3",
                        positions.flow(FLOW, INSTRUMENT).orElseThrow().longQuantity()),
                () -> assertDecimal("3",
                        positions.partition(PARTITION, INSTRUMENT).orElseThrow().netQuantity()));
    }

    /**
     * Only a split the market data owner approved and confirmed may be applied. Canonical says
     * "confirmed" with an {@code AVAILABLE} source dataset and the absence of a later approved
     * revision, and
     * an action it never published cannot be applied at all — there is nothing this service could
     * derive that would stand in for one.
     */
    @Test
    void anActionTheMarketDataOwnerHasNotConfirmedIsRefused() {
        openLot(1, "1", "100", 1);
        UUID unpublished = UUID.fromString("3c000000-0000-4000-8000-0000000000ff");

        CorporateActionConflictException quarantined = assertThrows(
                CorporateActionConflictException.class,
                () -> store.apply(application(QUARANTINED, 2, 1, 21)));
        CorporateActionConflictException superseded = assertThrows(
                CorporateActionConflictException.class,
                () -> store.apply(application(SUPERSEDED, 2, 1, 21)));
        CorporateActionConflictException missing = assertThrows(
                CorporateActionConflictException.class,
                () -> store.apply(application(unpublished, 2, 1, 21)));

        assertAll(
                () -> assertTrue(quarantined.getMessage().contains("QUARANTINED"),
                        quarantined::getMessage),
                () -> assertTrue(superseded.getMessage().contains("superseded"),
                        superseded::getMessage),
                () -> assertTrue(missing.getMessage().contains("no confirmed corporate action"),
                        missing::getMessage),
                () -> assertDecimal("1",
                        positions.flow(FLOW, INSTRUMENT).orElseThrow().longQuantity()));
    }

    /** A candidate nobody approved never reaches the store at all. */
    @Test
    void theDomainRejectsAnUnapprovedCandidateBeforePersistence() {
        assertThrows(IllegalArgumentException.class, () -> new ApprovedCorporateAction(
                SPLIT, INSTRUMENT, CorporateActionType.SPLIT, 2, 1, EFFECTIVE_AT,
                CorporateActionApprovalStatus.PENDING, UUID.randomUUID(), UUID.randomUUID(), T0,
                termsHash(SPLIT), "split-v1"));
    }

    /** Canonical has no movement for a ratio that changes nothing, so the wrapper refuses one. */
    @Test
    void aRatioThatChangesNoQuantityHasNoCanonicalMovement() {
        assertThrows(IllegalArgumentException.class, () -> new CorporateActionApplication(
                approved(SPLIT, 1, 1), BOT, EVENTS.get(21)));
    }

    private static CorporateActionApplication application(
            UUID actionId, long numerator, long denominator, int eventIndex) {
        return new CorporateActionApplication(
                approved(actionId, numerator, denominator), BOT, EVENTS.get(eventIndex));
    }

    private static ApprovedCorporateAction approved(
            UUID actionId, long numerator, long denominator) {
        return new ApprovedCorporateAction(
                actionId, INSTRUMENT, CorporateActionType.SPLIT, numerator, denominator,
                EFFECTIVE_AT, CorporateActionApprovalStatus.APPROVED, UUID.randomUUID(),
                UUID.randomUUID(), T0, termsHash(actionId), "split-v1");
    }

    /**
     * The canonical {@code terms_hash} is what the approval's evidence digest identifies: the exact
     * terms document the market data owner published and hashed.
     */
    private static String termsHash(UUID actionId) {
        String hex = actionId.toString().replace("-", "");
        return (hex + hex).substring(0, 64);
    }

    private static String manifest(UUID id, String status, int revision) {
        return """
                insert into market_data.dataset_manifests (id, feed_id, instrument_id, data_layer,
                    resolution, revision_number, status, period_start, period_end, schema_version,
                    dataset_hash, available_at)
                values ('%s', gen_random_uuid(), '%s', 'NORMALIZED', 'EVENT', %d, '%s',
                    '2026-08-01T00:00:00+00', '2026-08-03T00:00:00+00', 'v1', '%s',
                    '2026-08-01T00:00:00+00')
                """.formatted(id, INSTRUMENT, revision, status, termsHash(id));
    }

    private static String corporateAction(
            UUID id, UUID manifestId, String providerEventKey, long to, long from, UUID supersedes) {
        String review = supersedes == null
                ? ""
                : ",\"review\":{\"state\":\"APPROVED\"}";
        return """
                insert into market_data.corporate_actions (id, instrument_id, source_manifest_id,
                    provider_event_key, action_type, effective_at, terms_document, terms_hash,
                    supersedes_action_id)
                values ('%s', '%s', '%s', '%s', 'SPLIT', '%s',
                    '{"actionType":"SPLIT","ratio":{"from":%d,"to":%d}%s}', '%s', %s)
                """.formatted(id, INSTRUMENT, manifestId, providerEventKey, EFFECTIVE_AT, from, to,
                review, termsHash(id), supersedes == null ? "null" : "'" + supersedes + "'");
    }

    /** Opens one real canonical lot through the order, fill and position write paths. */
    private static void openLot(int index, String quantity, String price, int secondsIn) {
        UUID intentId = UUID.fromString("39000000-0000-4000-8000-%012d".formatted(index));
        UUID candidateId = UUID.fromString("3a000000-0000-4000-8000-%012d".formatted(index));
        // Canonical makes order_events.bot_event_id unique, so acceptance and the fill of each
        // trade need an official event of their own.
        UUID acceptedEventId = EVENTS.get(index * 2 - 2);
        UUID botEventId = EVENTS.get(index * 2 - 1);
        BigDecimal size = new BigDecimal(quantity);
        BigDecimal unitPrice = new BigDecimal(price);
        BigDecimal gross = size.multiply(unitPrice).setScale(8);
        BigDecimal fee = gross.multiply(new BigDecimal("0.002")).setScale(8);
        BigDecimal cash = gross.add(fee).negate();
        Instant occurredAt = T0.plusSeconds(secondsIn);

        jdbc.sql("""
                insert into trading.order_intents (id, bot_id, batch_id, source_event_id,
                    origin_type, evaluation_run_id, partition_id, flow_id, instrument_id,
                    intent_key, side, position_effect, order_type, time_in_force,
                    requested_quantity, post_netting_quantity, final_quantity, decision,
                    decision_reason_code)
                values (:id, :bot, :batch, :event, 'FLOW_EVALUATION', :evaluation, :partition,
                    :flow, :instrument, :key, 'BUY', 'OPEN_LONG', 'MARKET', 'DAY',
                    :quantity, :quantity, :quantity, 'APPROVED', 'ELIGIBLE')
                """)
                .param("id", intentId).param("bot", BOT).param("batch", INTENT_BATCH)
                .param("event", EVENTS.getFirst()).param("evaluation", EVALUATION)
                .param("partition", PARTITION).param("flow", FLOW).param("instrument", INSTRUMENT)
                .param("key", "candidate:" + candidateId).param("quantity", size).update();

        OrderLifecycle lifecycle = new OrderLifecycleFactory().accepted(new OrderTerms(
                intentId, candidateId, INSTRUMENT, OrderSide.BUY, size, OrderType.MARKET,
                TimeInForce.DAY, null, null, null, null), T0);
        orders.createOrLoad(new OrderPlacement(
                lifecycle, SCOPE, PINS, acceptedEventId,
                List.of(new OrderComponent(intentId, size, 1))));
        UUID orderId = lifecycle.orderId();
        UUID componentId = jdbc.sql(
                        "select id from trading.order_components where order_id = :orderId")
                .param("orderId", orderId).query(UUID.class).single();

        FillRecord record = FillRecord.original(
                orderId, "execution-" + index, size, unitPrice, fee, new BigDecimal("0.01"),
                occurredAt, occurredAt);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            fills.appendOrLoad(new FillPosting(
                    record, SCOPE, botEventId, FEE_POLICY, 20, "precision-rules:v1", unitPrice,
                    occurredAt, "m".repeat(64), gross, gross, cash, "fill-allocation:v1",
                    List.of(new FillAllocation(componentId, 1, size, gross, fee, cash))));
            orders.apply(new FillOrderCommand(
                    UUID.randomUUID(), orderId, botEventId, 1, size, occurredAt));
        });

        UUID allocationId = jdbc.sql(
                        "select id from trading.fill_component_allocations where fill_id = :fillId")
                .param("fillId", record.fillRecordId()).query(UUID.class).single();
        assertNotNull(allocationId);
        lots.open(new LotOpening(
                SCOPE, FLOW, INSTRUMENT, componentId, allocationId, botEventId, LotSide.LONG, size,
                gross, fee, occurredAt));
    }

    private static PostgresCorporateActionStore newStore() {
        return new PostgresCorporateActionStore(JdbcClient.create(dataSource), transactions);
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "expected " + expected + " but was " + actual);
    }
}
