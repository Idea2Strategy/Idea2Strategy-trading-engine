package com.idea2strategy.trading.persistence.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The module migration directory is a frozen private compatibility schema that reconstructs the
 * pre-canonical F01-F14 persistence tests. It is not a canonical contribution, so no later issue
 * may grow it. New canonical DDL belongs in {@code db/migration-contributions/migrations} and is
 * applied only by the central Flyway deployment.
 */
class RuntimeMigrationBoundaryTest {

    private static final List<String> FROZEN_COMPATIBILITY_MIGRATIONS = List.of(
            "V2026080101__create_candidate_batch_processing.sql",
            "V2026080102__create_order_intent_identity_batch.sql",
            "V2026080103__create_order_lifecycle.sql",
            "V2026080201__create_resource_reservations.sql",
            "V2026080202__create_virtual_fill_decisions.sql",
            "V2026080203__create_execution_fill_records.sql",
            "V2026080204__create_official_double_entry_ledger.sql",
            "V2026080205__create_position_lots.sql",
            "V2026080206__create_short_borrow_fee_accrual.sql",
            "V2026080207__create_corporate_action_applications.sql",
            "V2026080208__create_bot_stop_settlement.sql");

    @Test
    void theRuntimeCompatibilitySchemaStaysFrozen() throws IOException {
        Path runtimeMigrations = repositoryRoot()
                .resolve("modules/trading-persistence/src/main/resources/db/migration");
        assertTrue(Files.isDirectory(runtimeMigrations));

        List<String> actual;
        try (Stream<Path> files = Files.list(runtimeMigrations)) {
            actual = files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        }

        assertEquals(
                FROZEN_COMPATIBILITY_MIGRATIONS,
                actual,
                "the private compatibility schema changed. Canonical DDL must be contributed "
                        + "through db/migration-contributions/migrations instead.");
    }

    @Test
    void candidateReceiptCompatibilityMigrationToleratesTheCanonicalTable() throws IOException {
        String sql = Files.readString(repositoryRoot().resolve(
                "modules/trading-persistence/src/main/resources/db/migration/"
                        + "V2026080101__create_candidate_batch_processing.sql"));

        assertTrue(
                sql.toLowerCase().contains("create table if not exists trading.candidate_batch_processing"),
                "the private compatibility migration must tolerate a canonical table applied first");
        assertTrue(
                sql.toLowerCase().contains("create index if not exists candidate_batch_processing_evaluation_idx"),
                "the private compatibility migration must tolerate the canonical index applied first");
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
