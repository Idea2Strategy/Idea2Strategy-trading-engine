package com.idea2strategy.trading.persistence.corporateaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.idea2strategy.trading.application.corporateaction.CorporateActionConflictException;
import com.idea2strategy.trading.domain.corporateaction.ApprovedCorporateAction;
import com.idea2strategy.trading.domain.corporateaction.CorporateActionApprovalStatus;
import com.idea2strategy.trading.domain.corporateaction.CorporateActionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Corporate actions still adjust the private {@code execution_*} position tables, so this test seeds
 * those tables directly instead of borrowing the position write path, which now writes canonical.
 */
@Testcontainers(disabledWithoutDocker = true)
class CorporateActionPersistenceTest {

    private static final UUID BOT = UUID.randomUUID();
    private static final UUID PARTITION = UUID.randomUUID();
    private static final UUID FLOW = UUID.randomUUID();
    private static final UUID INSTRUMENT = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-08-01T14:30:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    private static DriverManagerDataSource dataSource;
    private static JdbcClient jdbc;
    private static JdbcTransactionManager tx;
    private static PostgresCorporateActionStore store;

    @BeforeAll
    static void setup() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = JdbcClient.create(dataSource);
        tx = new JdbcTransactionManager(dataSource);
        store = new PostgresCorporateActionStore(jdbc, tx);
    }

    @BeforeEach
    void clear() {
        jdbc.sql("""
                truncate table trading.execution_corporate_action_application,
                    trading.execution_position_lot, trading.execution_flow_position_projection,
                    trading.execution_position_command cascade
                """).update();
    }

    @Test
    void approvedSplitAdjustsEveryOpenLotAndFlowExactlyOnceWhilePreservingBasis() {
        openLot(new BigDecimal("1.5"), new BigDecimal("100"), new BigDecimal("0.30"));
        ApprovedCorporateAction action = action(UUID.randomUUID(), 2, 1);

        var result = store.apply(action);
        assertEquals(result, new PostgresCorporateActionStore(JdbcClient.create(dataSource), tx).apply(action));

        assertEquals(1, result.adjustedLots());
        assertEquals(1, result.adjustedFlowPositions());
        assertEquals(PostgresCorporateActionStore.NO_MONETARY_POSTING, result.ledgerEffect());
        decimal("3", lotColumn("remaining_quantity"));
        decimal("150.30", lotColumn("remaining_cost_basis"));
        decimal("3", flowColumn("quantity"));
        decimal("150.30", flowColumn("cost_basis"));
        assertEquals(1, jdbc.sql("select count(*) from trading.execution_corporate_action_lot_adjustment")
                .query(Integer.class).single());
    }

    @Test
    void unrepresentableRatioRollsBackInsteadOfRoundingAndIdentityConflictDoesNotReapply() {
        openLot(BigDecimal.ONE, BigDecimal.TEN, new BigDecimal("0.02"));
        ApprovedCorporateAction unsupported = action(UUID.randomUUID(), 1, 3);

        assertThrows(ArithmeticException.class, () -> store.apply(unsupported));
        assertEquals(0, jdbc.sql("select count(*) from trading.execution_corporate_action_application")
                .query(Integer.class).single());

        ApprovedCorporateAction accepted = action(UUID.randomUUID(), 2, 1);
        store.apply(accepted);
        ApprovedCorporateAction conflict = new ApprovedCorporateAction(
                accepted.actionId(), INSTRUMENT, CorporateActionType.SPLIT, 3, 1,
                accepted.effectiveAt(), CorporateActionApprovalStatus.APPROVED, accepted.approvalId(),
                accepted.approvedByOperatorId(), accepted.approvedAt(), accepted.evidenceDigest(),
                accepted.policyVersion());

        assertThrows(CorporateActionConflictException.class, () -> store.apply(conflict));
        decimal("2", flowColumn("quantity"));
    }

    @Test
    void domainRejectsAiCandidateOrPendingApprovalBeforePersistence() {
        assertThrows(IllegalArgumentException.class, () -> new ApprovedCorporateAction(
                UUID.randomUUID(), INSTRUMENT, CorporateActionType.SPLIT, 2, 1, T0.plusSeconds(2),
                CorporateActionApprovalStatus.PENDING, UUID.randomUUID(), UUID.randomUUID(), T0,
                "a".repeat(64), "split-v1"));
    }

    private static void openLot(BigDecimal quantity, BigDecimal price, BigDecimal commission) {
        BigDecimal basis = quantity.multiply(price).add(commission);
        UUID lotId = UUID.randomUUID();
        OffsetDateTime at = T0.atOffset(ZoneOffset.UTC);
        jdbc.sql("""
                        insert into trading.execution_position_lot (lot_id, bot_id, partition_id,
                            flow_id, instrument_id, opening_fill_record_id, opened_quantity,
                            unit_price, opening_commission, opened_cost_basis, opened_at)
                        values (:lotId, :bot, :partition, :flow, :instrument, :fill, :quantity,
                            :price, :commission, :basis, :at)
                        """)
                .param("lotId", lotId).param("bot", BOT).param("partition", PARTITION)
                .param("flow", FLOW).param("instrument", INSTRUMENT).param("fill", UUID.randomUUID())
                .param("quantity", quantity).param("price", price).param("commission", commission)
                .param("basis", basis).param("at", at).update();
        jdbc.sql("""
                        insert into trading.execution_position_lot_projection (lot_id,
                            remaining_quantity, remaining_cost_basis, version, closed_at, updated_at)
                        values (:lotId, :quantity, :basis, 1, null, :at)
                        """)
                .param("lotId", lotId).param("quantity", quantity).param("basis", basis)
                .param("at", at).update();
        jdbc.sql("""
                        insert into trading.execution_flow_position_projection (bot_id, partition_id,
                            flow_id, instrument_id, quantity, cost_basis, realized_pnl, version,
                            updated_at)
                        values (:bot, :partition, :flow, :instrument, :quantity, :basis, 0, 1, :at)
                        on conflict (bot_id, partition_id, flow_id, instrument_id) do update set
                            quantity = trading.execution_flow_position_projection.quantity
                                + excluded.quantity,
                            cost_basis = trading.execution_flow_position_projection.cost_basis
                                + excluded.cost_basis,
                            version = trading.execution_flow_position_projection.version + 1,
                            updated_at = excluded.updated_at
                        """)
                .param("bot", BOT).param("partition", PARTITION).param("flow", FLOW)
                .param("instrument", INSTRUMENT).param("quantity", quantity).param("basis", basis)
                .param("at", at).update();
    }

    private static BigDecimal lotColumn(String column) {
        return jdbc.sql("select " + column + " from trading.execution_position_lot_projection")
                .query(BigDecimal.class).single();
    }

    private static BigDecimal flowColumn(String column) {
        return jdbc.sql("select " + column + " from trading.execution_flow_position_projection")
                .query(BigDecimal.class).single();
    }

    private static ApprovedCorporateAction action(UUID id, long numerator, long denominator) {
        return new ApprovedCorporateAction(id, INSTRUMENT, CorporateActionType.SPLIT, numerator,
                denominator, T0.plusSeconds(10), CorporateActionApprovalStatus.APPROVED,
                UUID.randomUUID(), UUID.randomUUID(), T0, "a".repeat(64), "split-v1");
    }

    private static void decimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
