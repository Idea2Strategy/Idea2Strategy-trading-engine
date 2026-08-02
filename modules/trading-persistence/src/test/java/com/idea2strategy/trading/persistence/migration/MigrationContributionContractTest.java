package com.idea2strategy.trading.persistence.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class MigrationContributionContractTest {

    private static final Pattern MIGRATION_NAME =
            Pattern.compile("^V[0-9]{14}__trading_[a-z0-9]+(?:_[a-z0-9]+)*[.]sql$");

    @Test
    void exposesMachineReadableCentralBundleBoundary() throws IOException {
        Path repository = repositoryRoot();
        Path contributionRoot = repository.resolve("db/migration-contributions");
        Properties contract = load(contributionRoot.resolve("contribution.properties"));

        assertEquals("1", contract.getProperty("contract.version"));
        assertEquals("trading", contract.getProperty("owner"));
        assertEquals("bot,trading", contract.getProperty("schemas"));
        assertEquals("migrations", contract.getProperty("migrations.directory"));
        assertEquals("fixtures", contract.getProperty("fixtures.directory"));
        assertEquals(MIGRATION_NAME.pattern(), contract.getProperty("filename.regex"));
        assertEquals("false", contract.getProperty("runtime.flyway.enabled"));

        Path migrations = contributionRoot.resolve(contract.getProperty("migrations.directory"));
        Path fixtures = contributionRoot.resolve(contract.getProperty("fixtures.directory"));
        assertTrue(Files.isDirectory(migrations));
        assertTrue(Files.isDirectory(fixtures));

        try (var files = Files.list(migrations)) {
            List<String> invalidNames = files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".sql"))
                    .filter(name -> !MIGRATION_NAME.matcher(name).matches())
                    .toList();
            assertTrue(invalidNames.isEmpty(), () -> "invalid canonical migration names: " + invalidNames);
        }
        try (var files = Files.walk(fixtures)) {
            assertFalse(files.filter(Files::isRegularFile)
                    .anyMatch(path -> path.getFileName().toString().endsWith(".sql")),
                    "fixture files must never be discoverable as Flyway migrations");
        }
    }

    private static Properties load(Path path) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        }
        return properties;
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
