package com.idea2strategy.trading.common.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/** Verifies the immutable-source receipt written by the host materializer before startup. */
public final class MaterializationReceipt {
    private static final String CONTRACT = "i2s.materialization-receipt";
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    private MaterializationReceipt() {}

    public static void verify(Path receiptPath, Map<String, Path> requiredArtifacts) {
        Objects.requireNonNull(receiptPath, "receiptPath");
        Objects.requireNonNull(requiredArtifacts, "requiredArtifacts");
        Path receipt = receiptPath.toAbsolutePath().normalize();
        requireRegularFile(receipt, "materialization receipt");

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(receipt)) {
            properties.load(input);
        } catch (IOException exception) {
            throw new IllegalStateException("materialization receipt cannot be read: " + receipt, exception);
        }
        require(CONTRACT.equals(properties.getProperty("contract")), "unsupported materialization receipt contract");
        require("1".equals(properties.getProperty("schema-version")), "unsupported materialization receipt schema-version");

        int count = positiveInt(properties.getProperty("artifact-count"), "artifact-count");
        Map<String, Path> receipted = new HashMap<>();
        Set<Path> paths = new HashSet<>();
        for (int index = 0; index < count; index++) {
            String prefix = "artifact." + index + ".";
            String id = required(properties, prefix + "id");
            required(properties, prefix + "source-bucket");
            required(properties, prefix + "source-key");
            required(properties, prefix + "source-version-id");
            String declaredHash = required(properties, prefix + "sha256");
            require(SHA_256.matcher(declaredHash).matches(), prefix + "sha256 must be lowercase SHA-256");
            Path localPath = Path.of(required(properties, prefix + "local-path")).toAbsolutePath().normalize();
            require(receipted.putIfAbsent(id, localPath) == null, "duplicate materialization artifact id: " + id);
            require(paths.add(localPath), "duplicate materialization local-path: " + localPath);
            requireRegularFile(localPath, "materialized artifact " + id);
            require(declaredHash.equals(sha256(localPath)), "materialization checksum mismatch for " + id);
        }

        requiredArtifacts.forEach((id, expectedPath) -> {
            Path expected = Objects.requireNonNull(expectedPath, "expectedPath").toAbsolutePath().normalize();
            Path actual = receipted.get(id);
            require(actual != null, "required materialization artifact is missing: " + id);
            require(actual.equals(expected), "materialization path mismatch for " + id);
        });
    }

    private static void requireRegularFile(Path path, String description) {
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), description + " is missing or is a symbolic link: " + path);
    }

    private static String required(Properties properties, String name) {
        String value = properties.getProperty(name);
        require(value != null && !value.isBlank(), "materialization receipt field is required: " + name);
        return value.trim();
    }

    private static int positiveInt(String value, String name) {
        try {
            int parsed = Integer.parseInt(value);
            require(parsed > 0, name + " must be positive");
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(name + " must be a positive integer", exception);
        }
    }

    private static String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
                input.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException exception) {
            throw new IllegalStateException("materialized artifact cannot be read: " + path, exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

}
