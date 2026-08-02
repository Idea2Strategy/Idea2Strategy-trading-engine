package com.idea2strategy.trading.persistence.canonical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The pinned canonical baseline is the only way this repository can test the canonical schema it
 * writes to, so a corrupted, truncated or hand-edited copy must fail the build loudly.
 */
@Testcontainers(disabledWithoutDocker = true)
class CanonicalBaselineContractTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final List<String> APPLICATION_SCHEMAS = List.of(
            "identity", "strategy", "bot", "storage", "market_data",
            "trading", "backtest", "performance", "competition", "operations");

    private static JdbcClient jdbc;

    @BeforeAll
    static void migrateCanonicalBaseline() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        CanonicalBaseline.migrate(dataSource);
        jdbc = JdbcClient.create(dataSource);
    }

    @Test
    void everyPinnedMigrationMatchesItsRecordedDigest() {
        Map<String, String> recorded = CanonicalBaseline.manifestDigests();
        assertFalse(recorded.isEmpty(), "the canonical baseline manifest records nothing");
        assertEquals(
                recorded.keySet().stream().sorted().toList(),
                CanonicalBaseline.presentMigrations(),
                "the pinned baseline directory and its manifest disagree about which migrations exist");
        recorded.forEach((fileName, digest) -> assertEquals(
                digest,
                CanonicalBaseline.actualDigest(fileName),
                fileName + " does not match its recorded digest. Never hand-edit the pinned baseline; "
                        + "refresh it from the assembled central bundle instead."));
        assertEquals(
                CanonicalBaseline.recordedManifestDigest(),
                CanonicalBaseline.actualManifestDigest(),
                "the canonical baseline manifest does not match its recorded digest");
    }

    @Test
    void theBaselineStandsUpTheWholeCanonicalSchema() {
        int tables = jdbc.sql("""
                        select count(*) from information_schema.tables
                         where table_schema = any (?) and table_type = 'BASE TABLE'
                        """)
                .param(APPLICATION_SCHEMAS.toArray(String[]::new))
                .query(Integer.class)
                .single();
        assertEquals(152, tables, "the pinned canonical baseline no longer produces the canonical schema");
    }

    @Test
    void theCanonicalTradingWritePathTablesExist() {
        List<String> required = List.of(
                "order_intent_batches", "order_intents", "orders", "order_components",
                "order_events", "order_state_projections", "resource_reservations",
                "reservation_events", "order_component_reservations", "position_lot_reservations",
                "fills", "fill_component_allocations", "fill_adjustments",
                "ledger_accounts", "ledger_transactions", "ledger_entries",
                "position_lots", "lot_movements", "position_lot_projections",
                "flow_position_projections", "partition_position_projections",
                "bot_budget_projections", "partition_budget_projections",
                "system_close_actions", "short_borrow_fee_accruals");
        for (String table : required) {
            assertTrue(
                    exists("trading", table),
                    "canonical write-path table trading." + table + " is missing from the pinned baseline");
        }
        for (String table : List.of("bots", "bot_partitions", "flows")) {
            assertTrue(exists("bot", table), "canonical parent table bot." + table + " is missing");
        }
    }

    @Test
    void thePrivateCompatibilitySchemaIsNotPartOfTheCanonicalBaseline() {
        for (String table : List.of(
                "trading_order", "order_lifecycle_command", "order_lifecycle_transition",
                "execution_resource_reservation", "execution_fill_record",
                "official_ledger_transaction", "official_ledger_entry",
                "execution_position_lot", "execution_flow_position_projection",
                "bot_stop_settlement", "virtual_fill_decision", "candidate_batch_processing")) {
            assertFalse(
                    exists("trading", table),
                    "trading." + table + " is a private compatibility relation and must never be "
                            + "part of the canonical baseline");
        }
    }

    private static boolean exists(String schema, String table) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        select exists (
                            select 1 from information_schema.tables
                             where table_schema = ? and table_name = ? and table_type = 'BASE TABLE')
                        """)
                .param(schema)
                .param(table)
                .query(Boolean.class)
                .single());
    }
}
