package com.idea2strategy.trading.persistence.canonical;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/**
 * The pinned canonical baseline in {@code db/canonical-baseline}.
 *
 * <p>The trading engine owns no canonical DDL, so this digest-pinned copy of the central Flyway
 * bundle is the only way its own tests can stand up the canonical schema they write to. It is a test
 * input, never a runtime migration source: Flyway is a test-only dependency and
 * {@code verifyRuntimeDatabaseBoundary} fails the build if it ever reaches the runtime classpath.
 */
public final class CanonicalBaseline {

    public static final String MANIFEST_HEADER = "idea2strategy-canonical-baseline-v1";

    private CanonicalBaseline() {}

    /** Absolute path of the pinned baseline directory. */
    public static Path directory() {
        return repositoryRoot().resolve("db/canonical-baseline");
    }

    /** Flyway location string for the pinned baseline. */
    public static String flywayLocation() {
        return "filesystem:" + directory().toAbsolutePath();
    }

    /** Flyway location string for this repository's canonical migration contributions. */
    public static String contributionLocation() {
        return "filesystem:" + repositoryRoot().resolve("db/migration-contributions/migrations")
                .toAbsolutePath();
    }

    /** Migrates the canonical baseline into an empty database. */
    public static void migrate(DataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations(flywayLocation())
                .load()
                .migrate();
    }

    /**
     * Migrates the canonical baseline and then this repository's own contributions on top, which is
     * what the central assembler produces once the contributions are folded into the bundle. This
     * is the only way a contribution can be proven against the canonical schema before it ships.
     */
    public static void migrateWithContributions(DataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations(flywayLocation(), contributionLocation())
                .load()
                .migrate();
    }

    /** Recorded file name to SHA-256 digest, in manifest order. */
    public static Map<String, String> manifestDigests() {
        Map<String, String> digests = new LinkedHashMap<>();
        String[] lines = normalize(read(directory().resolve("baseline.manifest"))).split("\n");
        if (lines.length < 2 || !MANIFEST_HEADER.equals(lines[0])) {
            throw new IllegalStateException("canonical baseline manifest header is invalid");
        }
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index];
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\t");
            if (parts.length != 2 || !parts[0].endsWith(".sql") || !parts[1].matches("[0-9a-f]{64}")) {
                throw new IllegalStateException("invalid canonical baseline manifest entry: " + line);
            }
            digests.put(parts[0], parts[1]);
        }
        return digests;
    }

    /** Recorded digest of the manifest itself. */
    public static String recordedManifestDigest() {
        return normalize(read(directory().resolve("baseline.sha256"))).strip();
    }

    /** Actual digest of the manifest file. */
    public static String actualManifestDigest() {
        return sha256(normalize(read(directory().resolve("baseline.manifest"))));
    }

    /** Actual digest of one baseline migration. */
    public static String actualDigest(String fileName) {
        return sha256(normalize(read(directory().resolve(fileName))));
    }

    /** Every {@code .sql} file actually present in the baseline directory, sorted. */
    public static java.util.List<String> presentMigrations() {
        try (Stream<Path> files = Files.list(directory())) {
            return files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".sql"))
                    .sorted()
                    .toList();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /**
     * CRLF is normalised to LF so the digests stay portable across checkouts and stay identical to
     * the entries in the root bundle's own manifest.
     */
    private static String normalize(String text) {
        return text.replace("\r\n", "\n");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException("unable to read " + path, failure);
        }
    }

    private static String sha256(String text) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required", unavailable);
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest(text.getBytes(StandardCharsets.UTF_8))) {
            hex.append(Character.forDigit((value >> 4) & 0xF, 16));
            hex.append(Character.forDigit(value & 0xF, 16));
        }
        return hex.toString();
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
