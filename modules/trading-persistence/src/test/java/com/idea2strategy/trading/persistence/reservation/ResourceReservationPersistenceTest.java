package com.idea2strategy.trading.persistence.reservation;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idea2strategy.trading.application.order.FillOrderCommand;
import com.idea2strategy.trading.application.reservation.ConsumeReservationCommand;
import com.idea2strategy.trading.application.reservation.ReleaseReservationCommand;
import com.idea2strategy.trading.application.reservation.ReservationConflictException;
import com.idea2strategy.trading.application.reservation.ReservationVersionConflictException;
import com.idea2strategy.trading.application.reservation.SettleReservationCommand;
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
import com.idea2strategy.trading.domain.reservation.LotReservationAllocation;
import com.idea2strategy.trading.domain.reservation.ReservationComponentLink;
import com.idea2strategy.trading.domain.reservation.ReservationEventType;
import com.idea2strategy.trading.domain.reservation.ReservationOpening;
import com.idea2strategy.trading.domain.reservation.ReservationPolicyPins;
import com.idea2strategy.trading.domain.reservation.ReservationPricing;
import com.idea2strategy.trading.domain.reservation.ReservationReleaseCause;
import com.idea2strategy.trading.domain.reservation.ReservationResourceType;
import com.idea2strategy.trading.domain.reservation.ReservationStatus;
import com.idea2strategy.trading.domain.reservation.ResourceReservation;
import com.idea2strategy.trading.persistence.canonical.CanonicalBaseline;
import com.idea2strategy.trading.persistence.fill.PostgresFillRecordStore;
import com.idea2strategy.trading.persistence.order.PostgresOrderLifecycleStore;
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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the reservation write path against the real canonical schema.
 *
 * <p>A canonical reservation cannot be invented. It belongs to an approved
 * {@code trading.order_intents} row, it can only be drawn on by a fill once
 * {@code trading.order_component_reservations} attaches it to the component that intent was composed
 * into, and two deferred triggers decide at commit whether any of it was consistent. Each test
 * therefore builds the real chain through the order, fill and position write paths.
 */
@Testcontainers(disabledWithoutDocker = true)
class ResourceReservationPersistenceTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    private static final UUID BOT = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID PARTITION = UUID.fromString("31000000-0000-4000-8000-000000000001");
    private static final UUID FLOW = UUID.fromString("32000000-0000-4000-8000-000000000001");
    private static final UUID EVALUATION = UUID.fromString("34000000-0000-4000-8000-000000000001");
    private static final UUID INSTRUMENT = UUID.fromString("36000000-0000-4000-8000-000000000001");
    private static final UUID FEE_POLICY = UUID.fromString("37000000-0000-4000-8000-000000000001");
    private static final UUID BUFFER_POLICY = UUID.fromString("37000000-0000-4000-8000-000000000002");
    private static final UUID SHORT_POLICY = UUID.fromString("37000000-0000-4000-8000-000000000003");
    private static final UUID INTENT_BATCH = UUID.fromString("38000000-0000-4000-8000-000000000001");

    private static final Instant T0 = Instant.parse("2026-08-02T14:30:00Z");
    private static final String PRECISION = "precision-rules:v1";
    private static final String MARKET_HASH = "m".repeat(64);

    private static final OrderScope SCOPE = new OrderScope(BOT, PARTITION);
    private static final OrderPolicyPins ORDER_PINS = new OrderPolicyPins(
            FEE_POLICY, "broker-rules:v1", PRECISION, "order-intent-composition:v1");
    private static final ReservationPolicyPins CASH_PINS =
            ReservationPolicyPins.buyingPower(BUFFER_POLICY, FEE_POLICY, PRECISION);

    private static final int EVENT_POOL = 40;
    private static final List<UUID> EVENTS = new ArrayList<>();

    private static DriverManagerDataSource dataSource;
    private static JdbcClient jdbc;
    private static JdbcTransactionManager transactions;
    private static PostgresResourceReservationStore store;
    private static JooqResourceReservationQuery query;
    private static PostgresOrderLifecycleStore orders;
    private static PostgresFillRecordStore fills;
    private static PostgresPositionLotStore lots;

    @BeforeAll
    static void migrateAndSeed() throws Exception {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        CanonicalBaseline.migrateWithContributions(dataSource);
        jdbc = JdbcClient.create(dataSource);
        transactions = new JdbcTransactionManager(dataSource);
        store = newStore();
        query = new JooqResourceReservationQuery(DSL.using(dataSource, SQLDialect.POSTGRES));
        orders = new PostgresOrderLifecycleStore(jdbc, transactions);
        fills = new PostgresFillRecordStore(jdbc, transactions);
        lots = new PostgresPositionLotStore(jdbc, transactions);

        for (int index = 1; index <= EVENT_POOL; index++) {
            EVENTS.add(UUID.fromString("33000000-0000-4000-8000-%012d".formatted(index)));
        }

        // bot.* and market_data.* belong to other services; seeded with referential triggers off
        // exactly as the canonical contract fixtures do. The writes under test run with them back on.
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("set session_replication_role = replica");
            statement.addBatch("""
                    insert into identity.accounts (id, lifecycle_status, status_changed_at, created_at)
                    values ('a0000000-0000-4000-8000-000000000003', 'ACTIVE',
                        '2026-08-01T00:00:00+00', '2026-08-01T00:00:00+00')
                    """);
            statement.addBatch("""
                    insert into bot.bots (id, owner_account_id, mode, name, lifecycle_status,
                        lifecycle_changed_at, created_at, execution_eligible_from)
                    values ('%s', 'a0000000-0000-4000-8000-000000000003', 'BASIC', 'Reservation bot',
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
                        values ('%s', '%s', %d, 'ORDER_LIFECYCLE', 'v1', gen_random_uuid(),
                            'reservation-seed-%d', '2026-08-01T00:00:00+00',
                            '2026-08-01T00:00:00+00', '{}')
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
                insert into trading.buying_power_buffer_policy_versions (id, policy_code, version,
                    buffer_bps, rounding_rules_version, rules_hash, effective_from, published_at)
                values (:id, 'BUYING_POWER_BUFFER', 'v1', 75, 'rounding:v1', :hash,
                    '2026-01-01T00:00:00+00', '2026-01-01T00:00:00+00')
                """).param("id", BUFFER_POLICY).param("hash", "b".repeat(64)).update();
        jdbc.sql("""
                insert into trading.short_risk_policy_versions (id, policy_code, version,
                    rules_document, rules_hash, effective_from, published_at)
                values (:id, 'SHORT_RISK', 'v1', '{}', :hash,
                    '2026-01-01T00:00:00+00', '2026-01-01T00:00:00+00')
                """).param("id", SHORT_POLICY).param("hash", "s".repeat(64)).update();
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
     * Removed in one transaction with the checks deferred. The reservation triggers fire per
     * statement otherwise, and a half-cleared reservation legitimately fails them on the way out.
     */
    @BeforeEach
    void clearTradingRows() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("set constraints all deferred");
            statement.addBatch("delete from trading.reservation_events");
            statement.addBatch("delete from trading.order_component_reservations");
            statement.addBatch("delete from trading.position_lot_reservations");
            statement.addBatch("delete from trading.resource_reservations");
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

    @Test
    void aBuyingPowerReservationLandsInTheCanonicalCashShapeWithItsCreatedEvent() {
        UUID intentId = intent(1, OrderSide.BUY, "OPEN_LONG", decimal("2"));

        ResourceReservation reserved = store.createOrLoad(cashOpening(intentId, 2));

        var stored = query.findByReservationId(reserved.reservationId()).orElseThrow();
        var events = query.findEvents(reserved.reservationId());

        assertAll(
                () -> assertEquals(intentId, stored.intentId(),
                        "a canonical reservation belongs to the intent, not to an order"),
                () -> assertEquals("CASH_BUYING_POWER:USD", stored.reservationKey()),
                () -> assertEquals(ReservationResourceType.CASH_BUYING_POWER, stored.resourceType()),
                () -> assertEquals("USD", stored.currencyCode()),
                () -> assertEquals(null, stored.instrumentId(),
                        "cash buying power names no instrument"),
                () -> assertEquals(BUFFER_POLICY, stored.buffer().orElseThrow()),
                () -> assertEquals(FEE_POLICY, stored.fee().orElseThrow()),
                () -> assertTrue(stored.shortRisk().isEmpty(),
                        "short_policy_only_for_collateral forbids a short pin here"),
                () -> assertDecimal("20.20", stored.reservedAmount()),
                () -> assertEquals(null, stored.reservedQuantity(),
                        "reservation_exactly_one_measure allows only one measure"),
                () -> assertDecimal("0.15", stored.bufferAmount()),
                () -> assertEquals(FLOW, stored.flowId()),
                () -> assertEquals(ReservationStatus.ACTIVE, stored.status()),
                () -> assertEquals(1, stored.lastEventSequence()),
                () -> assertEquals(1, events.size()),
                () -> assertEquals(ReservationEventType.CREATED, events.getFirst().eventType()),
                () -> assertEquals("CREATED", events.getFirst().eventKey()),
                () -> assertEquals(1, events.getFirst().sequence()),
                () -> assertEquals(64, events.getFirst().eventHash().length(),
                        "the private request fingerprint lives on as event_hash"),
                () -> assertEquals(reserved, store.createOrLoad(cashOpening(intentId, 2)),
                        "the reservation reads back as it was written"));
    }

    @Test
    void aQuantityReservationLocksItsLotsAndPinsNoCashPolicy() {
        Held held = heldLot("2", "10", 1);
        UUID sellIntent = intent(2, OrderSide.SELL, "CLOSE_LONG", decimal("2"));

        ResourceReservation reserved = store.createOrLoad(new ReservationOpening(
                ResourceReservation.positionQuantity(
                        sellIntent, INSTRUMENT, decimal("2"),
                        List.of(new LotReservationAllocation(held.lotId(), held.openedAt(), decimal("2"))),
                        T0.plusSeconds(10)),
                SCOPE, FLOW, EVENTS.get(20),
                ReservationPolicyPins.positionQuantity(PRECISION), ReservationPricing.none()));

        var stored = query.findByReservationId(reserved.reservationId()).orElseThrow();
        var locks = query.findLotLocks(reserved.reservationId());

        assertAll(
                () -> assertEquals(ReservationResourceType.POSITION_QUANTITY, stored.resourceType()),
                () -> assertEquals(INSTRUMENT, stored.instrumentId()),
                () -> assertEquals(null, stored.currencyCode()),
                () -> assertEquals(null, stored.reservedAmount()),
                () -> assertDecimal("2", stored.reservedQuantity()),
                () -> assertTrue(stored.buffer().isEmpty() && stored.fee().isEmpty(),
                        "cash_policies_only_for_buying_power forbids cash pins here"),
                () -> assertEquals(1, locks.size()),
                () -> assertEquals(held.lotId(), locks.getFirst().positionLotId()),
                () -> assertDecimal("2", locks.getFirst().reservedQuantity()),
                () -> assertDecimal("2", locks.getFirst().activeReservedQuantity(),
                        "the lock reaches the lot projection the position path leaves alone"),
                () -> assertDecimal("2", locks.getFirst().remainingQuantity()),
                () -> assertEquals(List.of(held.lotId()),
                        reserved.lotAllocations().stream()
                                .map(LotReservationAllocation::lotId).toList()));
    }

    @Test
    void aShortCollateralReservationPinsTheShortRiskPolicyAndBothResourceKeys() {
        UUID intentId = intent(3, OrderSide.SELL, "OPEN_SHORT", decimal("2"));

        ResourceReservation reserved = store.createOrLoad(new ReservationOpening(
                ResourceReservation.shortCollateral(
                        intentId, "USD", INSTRUMENT, decimal("30.05"), T0),
                SCOPE, FLOW, EVENTS.get(21),
                ReservationPolicyPins.shortCollateral(SHORT_POLICY, PRECISION),
                ReservationPricing.shortCollateral(
                        decimal("10"), T0, MARKET_HASH, decimal("30"), decimal("0.01"),
                        decimal("0.04"))));

        var stored = query.findByReservationId(reserved.reservationId()).orElseThrow();

        assertAll(
                () -> assertEquals("SHORT_COLLATERAL_CASH:USD:" + INSTRUMENT,
                        stored.reservationKey()),
                () -> assertEquals("USD", stored.currencyCode()),
                () -> assertEquals(INSTRUMENT, stored.instrumentId()),
                () -> assertEquals(SHORT_POLICY, stored.shortRisk().orElseThrow()),
                () -> assertTrue(stored.buffer().isEmpty() && stored.fee().isEmpty()),
                () -> assertEquals(null, stored.bufferAmount(),
                        "cash_policies_only_for_buying_power forbids a buffer on collateral"),
                () -> assertDecimal("30.05", stored.reservedAmount()));
    }

    /**
     * Canonical makes {@code (intent_id, reservation_key)} unique and the reservation id is derived
     * from exactly that pair, so a redelivery lands on the row it already wrote. No private receipt
     * table is involved.
     */
    @Test
    void redeliveringTheSameReservationWritesNoSecondRowOrEvent() {
        UUID intentId = intent(4, OrderSide.BUY, "OPEN_LONG", decimal("2"));

        ResourceReservation first = store.createOrLoad(cashOpening(intentId, 2));
        ResourceReservation again = newStore().createOrLoad(cashOpening(intentId, 2));

        assertAll(
                () -> assertEquals(first, again),
                () -> assertEquals(1, query.findByIntentId(intentId).size()),
                () -> assertEquals(1, query.findEvents(first.reservationId()).size()));
    }

    /** A redelivery that claims something different is a conflict, not a silent no-op. */
    @Test
    void aReservationRedeliveredWithDifferentContentIsAConflict() {
        UUID intentId = intent(5, OrderSide.BUY, "OPEN_LONG", decimal("2"));
        ResourceReservation first = store.createOrLoad(cashOpening(intentId, 2));

        ReservationOpening divergent = new ReservationOpening(
                ResourceReservation.cash(intentId, "USD", decimal("40.40"), T0),
                SCOPE, FLOW, EVENTS.get(2), CASH_PINS,
                ReservationPricing.buyingPower(
                        decimal("10"), T0, MARKET_HASH, decimal("40"), decimal("0.02"),
                        decimal("0.08"), decimal("0.30")));

        ReservationConflictException failure =
                assertThrows(ReservationConflictException.class, () -> store.createOrLoad(divergent));

        assertAll(
                () -> assertTrue(failure.getMessage().contains("already exists")),
                () -> assertDecimal("20.20",
                        query.findByReservationId(first.reservationId()).orElseThrow()
                                .reservedAmount()),
                () -> assertEquals(1, query.findEvents(first.reservationId()).size()));
    }

    /**
     * The whole point of the partial-fill migration: consumption while {@code ACTIVE} is legal, and
     * the amount consumed has to be exactly the cash the fill allocation settled.
     */
    @Test
    void aPartialFillConsumesTheReservationAndLeavesItActive() {
        Traded traded = tradedIntent(6, decimal("2"));
        ResourceReservation reserved = attachedCashReservation(traded, 2);
        Filled filled = fill(traded, 1, decimal("1"), decimal("10"), 1, T0.plusSeconds(1));

        ResourceReservation consumed = store.apply(new ConsumeReservationCommand(
                reserved.reservationId(), 1, filled.botEventId(), filled.fillId(),
                filled.consumedCash(), T0.plusSeconds(1)));

        var stored = query.findByReservationId(reserved.reservationId()).orElseThrow();
        var events = query.findEvents(reserved.reservationId());

        assertAll(
                () -> assertEquals(ReservationStatus.ACTIVE, consumed.status()),
                () -> assertDecimal("10.02", stored.consumedAmount()),
                () -> assertDecimal("0", stored.releasedAmount(),
                        "active_reservation_not_released keeps the released total at zero"),
                () -> assertEquals(2, stored.lastEventSequence()),
                () -> assertEquals(2, events.size()),
                () -> assertEquals(ReservationEventType.CONSUMED_BY_FILL, events.get(1).eventType()),
                () -> assertEquals(ReservationStatus.ACTIVE, events.get(1).statusAfter()),
                () -> assertEquals(filled.fillId(), events.get(1).sourceFillId()),
                () -> assertDecimal("10.02", events.get(1).consumedAmountDelta()),
                () -> assertDecimal("10.18", consumed.remaining()));
    }

    @Test
    void theFinalFillSettlesTheReservationAndHandsBackTheBufferOnOneEvent() {
        Traded traded = tradedIntent(7, decimal("2"));
        ResourceReservation reserved = attachedCashReservation(traded, 2);
        Filled first = fill(traded, 1, decimal("1"), decimal("10"), 1, T0.plusSeconds(1));
        store.apply(new ConsumeReservationCommand(
                reserved.reservationId(), 1, first.botEventId(), first.fillId(),
                first.consumedCash(), T0.plusSeconds(1)));
        Filled last = fill(traded, 2, decimal("1"), decimal("10"), 2, T0.plusSeconds(2));

        ResourceReservation settled = store.apply(new SettleReservationCommand(
                reserved.reservationId(), 2, last.botEventId(), last.fillId(), last.consumedCash(),
                T0.plusSeconds(2)));

        var stored = query.findByReservationId(reserved.reservationId()).orElseThrow();
        var events = query.findEvents(reserved.reservationId());

        assertAll(
                () -> assertEquals(ReservationStatus.SETTLED, settled.status()),
                () -> assertDecimal("20.04", stored.consumedAmount()),
                () -> assertDecimal("0.16", stored.releasedAmount(),
                        "the buffer and the fee estimate error go back on the settling event"),
                () -> assertEquals(3, stored.lastEventSequence()),
                () -> assertEquals(ReservationEventType.SETTLED_BY_FILL, events.get(2).eventType()),
                () -> assertEquals(ReservationStatus.SETTLED, events.get(2).statusAfter()),
                () -> assertDecimal("10.02", events.get(2).consumedAmountDelta()),
                () -> assertDecimal("0.16", events.get(2).releasedAmountDelta()),
                () -> assertDecimal("20.20",
                        stored.consumedAmount().add(stored.releasedAmount()),
                        "reservation_amount_final_conservation"));
    }

    @Test
    void aCancelledIntentGivesTheWholeReservationBackAndDropsItsLotLocks() {
        Held held = heldLot("2", "10", 8);
        UUID sellIntent = intent(9, OrderSide.SELL, "CLOSE_LONG", decimal("2"));
        ResourceReservation reserved = store.createOrLoad(new ReservationOpening(
                ResourceReservation.positionQuantity(
                        sellIntent, INSTRUMENT, decimal("2"),
                        List.of(new LotReservationAllocation(held.lotId(), held.openedAt(), decimal("2"))),
                        T0.plusSeconds(10)),
                SCOPE, FLOW, EVENTS.get(22),
                ReservationPolicyPins.positionQuantity(PRECISION), ReservationPricing.none()));

        ResourceReservation released = store.apply(new ReleaseReservationCommand(
                reserved.reservationId(), 1, EVENTS.get(23), ReservationReleaseCause.CANCEL,
                T0.plusSeconds(11)));

        var stored = query.findByReservationId(reserved.reservationId()).orElseThrow();
        var events = query.findEvents(reserved.reservationId());
        var locks = query.findLotLocks(reserved.reservationId());

        assertAll(
                () -> assertEquals(ReservationStatus.RELEASED, released.status()),
                () -> assertEquals(ReservationReleaseCause.CANCEL, released.cause().orElseThrow()),
                () -> assertDecimal("0", stored.consumedQuantity()),
                () -> assertDecimal("2", stored.releasedQuantity()),
                () -> assertEquals(ReservationEventType.RELEASED_BY_CANCEL,
                        events.get(1).eventType()),
                () -> assertEquals("RELEASED:" + EVENTS.get(23), events.get(1).eventKey()),
                () -> assertDecimal("2", events.get(1).releasedQuantityDelta()),
                () -> assertDecimal("0", locks.getFirst().activeReservedQuantity(),
                        "a terminal reservation holds no lot remainder"));
    }

    /**
     * {@code assert_reservation_event_totals} rebuilds the projection from the events at commit, so
     * a projection nudged behind the event stream's back cannot commit at all.
     */
    @Test
    void theEventTotalTriggerRefusesAProjectionItsEventsDoNotExplain() {
        UUID intentId = intent(10, OrderSide.BUY, "OPEN_LONG", decimal("2"));
        ResourceReservation reserved = store.createOrLoad(cashOpening(intentId, 2));

        DataAccessException failure = assertThrows(DataAccessException.class, () -> jdbc.sql("""
                        update trading.resource_reservations set consumed_amount = 5
                        where id = :reservationId
                        """).param("reservationId", reserved.reservationId()).update());

        assertAll(
                () -> assertTrue(rootMessage(failure).contains("event totals do not match projection"),
                        () -> "unexpected failure: " + rootMessage(failure)),
                () -> assertDecimal("0", query.findByReservationId(reserved.reservationId())
                        .orElseThrow().consumedAmount()));
    }

    /**
     * {@code assert_fill_reservation_consumption} reaches from the event to the fill's component
     * allocation through {@code order_component_reservations}. A reservation that was never attached
     * cannot be drawn on, whichever way the event is written.
     */
    @Test
    void theFillConsumptionTriggerRefusesAnEventNoAllocationJustifies() {
        Traded traded = tradedIntent(11, decimal("2"));
        UUID unattachedIntent = intent(12, OrderSide.BUY, "OPEN_LONG", decimal("2"));
        ResourceReservation reserved = store.createOrLoad(cashOpening(unattachedIntent, 12));
        Filled filled = fill(traded, 1, decimal("2"), decimal("10"), 1, T0.plusSeconds(1));

        DataAccessException failure = assertThrows(DataAccessException.class, () -> jdbc.sql("""
                        insert into trading.reservation_events (
                            bot_id, partition_id, reservation_id, bot_event_id, source_fill_id,
                            event_key, reservation_sequence, event_type, consumed_amount_delta,
                            released_amount_delta, status_after, occurred_at, event_hash
                        ) values (
                            :bot, :partition, :reservationId, :botEvent, :fillId,
                            'CONSUMED_BY_FILL:forged', 2, 'CONSUMED_BY_FILL', 20.04, 0, 'ACTIVE',
                            :occurredAt, :hash
                        )
                        """)
                .param("bot", BOT).param("partition", PARTITION)
                .param("reservationId", reserved.reservationId())
                .param("botEvent", filled.botEventId()).param("fillId", filled.fillId())
                .param("occurredAt", T0.plusSeconds(1).atOffset(java.time.ZoneOffset.UTC))
                .param("hash", "0".repeat(64)).update());

        assertTrue(rootMessage(failure).contains("no matching component allocation"),
                () -> "unexpected failure: " + rootMessage(failure));
    }

    /** The store refuses the same thing first, with a message that says which fill was wrong. */
    @Test
    void aConsumptionTheFillAllocationDoesNotJustifyRollsBackWithoutAnEvent() {
        Traded traded = tradedIntent(13, decimal("2"));
        ResourceReservation reserved = attachedCashReservation(traded, 2);
        Filled filled = fill(traded, 1, decimal("1"), decimal("10"), 1, T0.plusSeconds(1));

        ReservationConflictException failure = assertThrows(ReservationConflictException.class,
                () -> store.apply(new ConsumeReservationCommand(
                        reserved.reservationId(), 1, filled.botEventId(), filled.fillId(),
                        decimal("9.99"), T0.plusSeconds(1))));

        var stored = query.findByReservationId(reserved.reservationId()).orElseThrow();
        assertAll(
                () -> assertTrue(failure.getMessage().contains("10.02")),
                () -> assertEquals(1, query.findEvents(reserved.reservationId()).size(),
                        "the refused change reached no table"),
                () -> assertDecimal("0", stored.consumedAmount()),
                () -> assertEquals(1, stored.lastEventSequence()));
    }

    @Test
    void aReservationCannotBeAttachedToAComponentComposedFromAnotherIntent() {
        Traded traded = tradedIntent(14, decimal("2"));
        UUID otherIntent = intent(15, OrderSide.BUY, "OPEN_LONG", decimal("2"));
        ResourceReservation reserved = store.createOrLoad(cashOpening(otherIntent, 15));

        ReservationConflictException failure = assertThrows(ReservationConflictException.class,
                () -> store.attachToOrderComponent(new ReservationComponentLink(
                        SCOPE, reserved.reservationId(), traded.componentId())));

        assertAll(
                () -> assertTrue(failure.getMessage().contains("different intent")),
                () -> assertTrue(query.findOrderComponentId(reserved.reservationId()).isEmpty()));
    }

    @Test
    void aStaleSequenceIsRefusedAndARedeliveredEventIsReplayed() {
        Traded traded = tradedIntent(16, decimal("2"));
        ResourceReservation reserved = attachedCashReservation(traded, 2);
        Filled filled = fill(traded, 1, decimal("1"), decimal("10"), 1, T0.plusSeconds(1));
        ConsumeReservationCommand consume = new ConsumeReservationCommand(
                reserved.reservationId(), 1, filled.botEventId(), filled.fillId(),
                filled.consumedCash(), T0.plusSeconds(1));

        ResourceReservation consumed = store.apply(consume);

        assertAll(
                () -> assertEquals(consumed, newStore().apply(consume),
                        "the same fill drawn twice reads back the event it already wrote"),
                () -> assertEquals(2, query.findEvents(reserved.reservationId()).size()),
                () -> assertThrows(ReservationVersionConflictException.class,
                        () -> store.apply(new ReleaseReservationCommand(
                                reserved.reservationId(), 1, EVENTS.get(24),
                                ReservationReleaseCause.EXPIRY, T0.plusSeconds(2)))),
                () -> assertThrows(ReservationConflictException.class,
                        () -> newStore().apply(new ConsumeReservationCommand(
                                reserved.reservationId(), 2, filled.botEventId(), filled.fillId(),
                                decimal("10.02"), T0.plusSeconds(9))),
                        "the same event key claiming different content is a conflict"));
    }

    /**
     * A release after a partial fill ends {@code SETTLED}, because
     * {@code released_reservation_has_no_consumption} keeps {@code RELEASED} for a reservation
     * nothing ever drew on.
     */
    @Test
    void aReleaseAfterAPartialFillSettlesRatherThanReleases() {
        Traded traded = tradedIntent(17, decimal("2"));
        ResourceReservation reserved = attachedCashReservation(traded, 2);
        Filled filled = fill(traded, 1, decimal("1"), decimal("10"), 1, T0.plusSeconds(1));
        store.apply(new ConsumeReservationCommand(
                reserved.reservationId(), 1, filled.botEventId(), filled.fillId(),
                filled.consumedCash(), T0.plusSeconds(1)));

        ResourceReservation ended = store.apply(new ReleaseReservationCommand(
                reserved.reservationId(), 2, EVENTS.get(25), ReservationReleaseCause.REPLACEMENT,
                T0.plusSeconds(3)));

        var stored = query.findByReservationId(reserved.reservationId()).orElseThrow();
        var events = query.findEvents(reserved.reservationId());

        assertAll(
                () -> assertEquals(ReservationStatus.SETTLED, ended.status()),
                () -> assertEquals(ReservationReleaseCause.REPLACEMENT, ended.cause().orElseThrow()),
                () -> assertEquals(ReservationEventType.RELEASED_BY_REPLACEMENT,
                        events.get(2).eventType()),
                () -> assertEquals(ReservationStatus.SETTLED, events.get(2).statusAfter()),
                () -> assertDecimal("10.18", stored.releasedAmount()),
                () -> assertDecimal("10.02", stored.consumedAmount()));
    }

    // ---------------------------------------------------------------- fixtures

    private static ReservationOpening cashOpening(UUID intentId, int eventIndex) {
        return new ReservationOpening(
                ResourceReservation.cash(intentId, "USD", decimal("20.20"), T0),
                SCOPE, FLOW, EVENTS.get(eventIndex), CASH_PINS,
                ReservationPricing.buyingPower(
                        decimal("10"), T0, MARKET_HASH, decimal("20"), decimal("0.01"),
                        decimal("0.04"), decimal("0.15")));
    }

    private static ResourceReservation attachedCashReservation(Traded traded, int eventIndex) {
        ResourceReservation reserved = store.createOrLoad(cashOpening(traded.intentId(), eventIndex));
        return store.attachToOrderComponent(
                new ReservationComponentLink(SCOPE, reserved.reservationId(), traded.componentId()));
    }

    /** An approved intent composed into an accepted order of one component. */
    private static Traded tradedIntent(int index, BigDecimal quantity) {
        UUID intentId = intent(index, OrderSide.BUY, "OPEN_LONG", quantity);
        UUID candidateId = UUID.fromString("3a000000-0000-4000-8000-%012d".formatted(index));
        OrderLifecycle lifecycle = new OrderLifecycleFactory().accepted(new OrderTerms(
                intentId, candidateId, INSTRUMENT, OrderSide.BUY, quantity, OrderType.MARKET,
                TimeInForce.DAY, null, null, null, null), T0);
        orders.createOrLoad(new OrderPlacement(
                lifecycle, SCOPE, ORDER_PINS, EVENTS.get(index * 2 - 2),
                List.of(new OrderComponent(intentId, quantity, 1))));
        UUID componentId = jdbc.sql("select id from trading.order_components where order_id = :orderId")
                .param("orderId", lifecycle.orderId()).query(UUID.class).single();
        return new Traded(intentId, lifecycle.orderId(), componentId, index);
    }

    /** One partial fill of the order, allocated whole to its single component. */
    private static Filled fill(
            Traded traded,
            int executionIndex,
            BigDecimal quantity,
            BigDecimal price,
            long expectedOrderVersion,
            Instant occurredAt) {
        UUID botEventId = EVENTS.get(traded.index() * 2 - 1 + executionIndex - 1);
        BigDecimal gross = quantity.multiply(price).setScale(8);
        BigDecimal fee = gross.multiply(new BigDecimal("0.002")).setScale(8);
        BigDecimal cash = gross.add(fee).negate();
        FillRecord record = FillRecord.original(
                traded.orderId(), "execution-%d-%d".formatted(traded.index(), executionIndex),
                quantity, price, fee, new BigDecimal("0.01"), occurredAt, occurredAt);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            fills.appendOrLoad(new FillPosting(
                    record, SCOPE, botEventId, FEE_POLICY, 20, PRECISION, price, occurredAt,
                    MARKET_HASH, gross, gross, cash, "fill-allocation:v1",
                    List.of(new FillAllocation(
                            traded.componentId(), 1, quantity, gross, fee, cash))));
            orders.apply(new FillOrderCommand(
                    UUID.randomUUID(), traded.orderId(), botEventId, expectedOrderVersion, quantity,
                    occurredAt));
        });
        return new Filled(record.fillRecordId(), botEventId, cash.abs());
    }

    /** A real FIFO lot, opened through the order, fill and position write paths. */
    private static Held heldLot(String quantity, String price, int index) {
        Traded traded = tradedIntent(index, decimal(quantity));
        Instant openedAt = T0.plusSeconds(1);
        Filled filled = fill(traded, 1, decimal(quantity), decimal(price), 1, openedAt);
        UUID allocationId = jdbc.sql(
                        "select id from trading.fill_component_allocations where fill_id = :fillId")
                .param("fillId", filled.fillId()).query(UUID.class).single();
        BigDecimal gross = decimal(quantity).multiply(decimal(price)).setScale(8);
        lots.open(new LotOpening(
                SCOPE, FLOW, INSTRUMENT, traded.componentId(), allocationId, filled.botEventId(),
                LotSide.LONG, decimal(quantity), gross,
                gross.multiply(new BigDecimal("0.002")).setScale(8), openedAt));
        UUID lotId = jdbc.sql("select id from trading.position_lots where flow_id = :flowId"
                        + " order by opened_at desc, id limit 1")
                .param("flowId", FLOW).query(UUID.class).single();
        return new Held(lotId, openedAt);
    }

    private static UUID intent(int index, OrderSide side, String effect, BigDecimal quantity) {
        UUID intentId = UUID.fromString("39000000-0000-4000-8000-%012d".formatted(index));
        jdbc.sql("""
                insert into trading.order_intents (id, bot_id, batch_id, source_event_id,
                    origin_type, evaluation_run_id, partition_id, flow_id, instrument_id,
                    intent_key, side, position_effect, order_type, time_in_force,
                    requested_quantity, post_netting_quantity, final_quantity, decision,
                    decision_reason_code)
                values (:id, :bot, :batch, :event, 'FLOW_EVALUATION', :evaluation, :partition,
                    :flow, :instrument, :key, cast(:side as trading.order_side),
                    cast(:effect as trading.position_effect), 'MARKET', 'DAY',
                    :quantity, :quantity, :quantity, 'APPROVED', 'ELIGIBLE')
                """)
                .param("id", intentId).param("bot", BOT).param("batch", INTENT_BATCH)
                .param("event", EVENTS.getFirst()).param("evaluation", EVALUATION)
                .param("partition", PARTITION).param("flow", FLOW).param("instrument", INSTRUMENT)
                .param("key", "candidate:" + intentId).param("side", side.name())
                .param("effect", effect).param("quantity", quantity).update();
        return intentId;
    }

    private static PostgresResourceReservationStore newStore() {
        return new PostgresResourceReservationStore(JdbcClient.create(dataSource), transactions);
    }

    private static String rootMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return String.valueOf(cause.getMessage());
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value).setScale(8);
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertDecimal(expected, actual, null);
    }

    private static void assertDecimal(String expected, BigDecimal actual, String message) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> (message == null ? "" : message + ": ") + "expected " + expected
                        + " but was " + actual);
    }

    private record Traded(UUID intentId, UUID orderId, UUID componentId, int index) {}

    private record Filled(UUID fillId, UUID botEventId, BigDecimal consumedCash) {}

    private record Held(UUID lotId, Instant openedAt) {}
}
