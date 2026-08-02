package com.idea2strategy.trading.persistence.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The trading engine owns no canonical DDL, so its own database can never hold the canonical read
 * model. This test pins the read contract itself: the statements must touch canonical tables only,
 * must be gated by the owning account, must page deterministically, and must be identical to the
 * statements that {@code db/migration-contributions/fixtures/
 * trading_read_projection_contract.sql.fixture} prepares and executes against a real migrated
 * canonical database in the central Flyway integration job.
 */
class CanonicalTradingReadContractTest {

    private static final Path FIXTURE = repositoryRoot()
            .resolve("db/migration-contributions/fixtures/trading_read_projection_contract.sql.fixture");

    private static final Pattern SOURCE_TABLE =
            Pattern.compile("(?:from|join)\\s+([a-z_]+\\.[a-z_]+)");

    private static final Set<String> CANONICAL_TABLES = Set.of(
            "bot.bots",
            "bot.bot_partitions",
            "bot.flows",
            "trading.bot_budget_projections",
            "trading.partition_budget_projections",
            "trading.resource_reservations",
            "trading.flow_position_projections",
            "trading.partition_position_projections",
            "trading.orders",
            "trading.order_state_projections",
            "trading.order_components",
            "trading.order_intents",
            "trading.order_intent_batches",
            "trading.order_events",
            "trading.fills",
            "trading.fill_component_allocations",
            "trading.ledger_transactions",
            "trading.ledger_entries",
            "trading.ledger_accounts",
            "trading.system_close_actions");

    private static final List<String> WITHDRAWN_RUNTIME_TABLES = List.of(
            "trading.execution_order_scope",
            "trading.execution_ledger_scope",
            "trading.execution_projection_reason",
            "trading.execution_flow_position_projection",
            "trading.execution_resource_reservation",
            "trading.execution_fill_record",
            "trading.official_ledger_transaction",
            "trading.official_ledger_entry",
            "trading.trading_order",
            "trading.bot_stop_settlement");

    @Test
    void everyStatementReadsCanonicalTablesOnly() {
        for (Map.Entry<String, String> statement : CanonicalTradingReadSql.ALL.entrySet()) {
            Set<String> referenced = referencedTables(statement.getValue());
            assertFalse(referenced.isEmpty(), statement.getKey() + " reads nothing");
            for (String table : referenced) {
                assertTrue(
                        CANONICAL_TABLES.contains(table),
                        statement.getKey() + " reads a non-canonical relation: " + table);
            }
            for (String withdrawn : WITHDRAWN_RUNTIME_TABLES) {
                assertFalse(
                        statement.getValue().contains(withdrawn),
                        statement.getKey() + " still reads withdrawn runtime table " + withdrawn);
            }
        }
    }

    @Test
    void everyStatementIsGatedByTheOwningAccountAndTheRequestedScope() {
        for (Map.Entry<String, String> statement : CanonicalTradingReadSql.ALL.entrySet()) {
            String sql = statement.getValue();
            assertTrue(
                    sql.contains("b.owner_account_id = cast(? as uuid)"),
                    statement.getKey() + " does not filter by the owning account");
            assertTrue(
                    sql.contains("b.deleted_at is null"),
                    statement.getKey() + " reads a logically deleted bot");
            assertEquals(
                    0,
                    sql.indexOf("with owned_bot as ("),
                    statement.getKey() + " must resolve ownership before reading trading data");
            if (statement.getKey().startsWith("flow_")) {
                assertTrue(
                        sql.contains("join bot.flows f")
                                && sql.contains("f.partition_id = p.id"),
                        statement.getKey() + " does not bind the flow to its owning partition");
            }
        }
    }

    @Test
    void listStatementsPageDeterministically() {
        for (String key : CanonicalTradingReadSql.PAGED) {
            String sql = CanonicalTradingReadSql.ALL.get(key).stripTrailing();
            assertTrue(sql.contains(" order by "), key + " has no deterministic order");
            assertTrue(sql.endsWith("limit ? offset ?"), key + " does not page");
        }
        assertFalse(
                CanonicalTradingReadSql.BOT_BUDGET.contains("limit ?"),
                "the single row bot budget must not page");
    }

    @Test
    void scopeAndPageRejectRequestsThatWouldWidenTheReadSilently() {
        UUID id = UUID.randomUUID();
        assertThrows(
                IllegalArgumentException.class,
                () -> new TradingScope(id, id, null, id),
                "a flow scope without a partition would read across partitions");
        assertThrows(
                IllegalArgumentException.class,
                () -> TradingScope.ofBot(id, id).requireFlowId());
        assertThrows(
                IllegalArgumentException.class,
                () -> TradingScope.ofBot(id, id).requirePartitionId());
        assertThrows(IllegalArgumentException.class, () -> new ProjectionPage(0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProjectionPage(ProjectionPage.MAX_LIMIT + 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ProjectionPage(10, -1));
    }

    @Test
    void theCentralContractFixturePreparesTheExactShippedStatements() throws IOException {
        String rendered = renderPreparedStatements();
        Path generated = Path.of("build", "generated-canonical-read-contract.sql");
        Files.createDirectories(generated.getParent());
        Files.writeString(generated, rendered);

        assertTrue(
                Files.isRegularFile(FIXTURE),
                "the canonical read contract fixture is missing: " + FIXTURE);
        String fixture = normalize(Files.readString(FIXTURE));
        for (Map.Entry<String, String> block : preparedStatements().entrySet()) {
            assertTrue(
                    fixture.contains(normalize(block.getValue())),
                    "the fixture does not prepare the shipped statement '" + block.getKey()
                            + "'. Regenerate it from " + generated.toAbsolutePath());
        }
        for (String key : CanonicalTradingReadSql.ALL.keySet()) {
            assertTrue(
                    fixture.contains("EXECUTE projection_" + key + "("),
                    "the fixture never executes projection_" + key);
        }
    }

    private static Map<String, String> preparedStatements() {
        Map<String, String> blocks = new TreeMap<>();
        CanonicalTradingReadSql.ALL.forEach((key, sql) ->
                blocks.put(key, "PREPARE projection_" + key + " AS\n" + toNumberedPlaceholders(sql)));
        return blocks;
    }

    private static String renderPreparedStatements() {
        StringBuilder rendered = new StringBuilder();
        preparedStatements().forEach((key, block) -> rendered.append(block).append(";\n\n"));
        return rendered.toString();
    }

    /** PostgreSQL prepared statements use {@code $n}; JDBC uses {@code ?}. */
    private static String toNumberedPlaceholders(String sql) {
        StringBuilder converted = new StringBuilder(sql.length() + 16);
        int parameter = 0;
        for (int index = 0; index < sql.length(); index++) {
            char character = sql.charAt(index);
            if (character == '?') {
                converted.append('$').append(++parameter);
            } else {
                converted.append(character);
            }
        }
        return converted.toString();
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n").strip();
    }

    private static Set<String> referencedTables(String sql) {
        Set<String> tables = new LinkedHashSet<>();
        Matcher matcher = SOURCE_TABLE.matcher(sql);
        while (matcher.find()) {
            tables.add(matcher.group(1));
        }
        return tables;
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("trading-engine repository root not found");
        }
        return current;
    }
}
