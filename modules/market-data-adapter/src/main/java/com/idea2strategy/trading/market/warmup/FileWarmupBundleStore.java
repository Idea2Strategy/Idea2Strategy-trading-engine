package com.idea2strategy.trading.market.warmup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public final class FileWarmupBundleStore implements WarmupBundleStore {
    private final Path root;

    public FileWarmupBundleStore(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    @Override
    public Optional<byte[]> read(String objectKey) throws IOException {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("objectKey must not be blank");
        }
        String normalized = objectKey.replace('\\', '/');
        Path name = Path.of(normalized).getFileName();
        if (name == null || name.toString().isBlank()) {
            throw new IllegalArgumentException("objectKey must identify a file");
        }
        Path candidate = root.resolve(name).normalize();
        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException("objectKey escapes the bundle root");
        }
        if (!Files.isRegularFile(candidate)) {
            return Optional.empty();
        }
        return Optional.of(Files.readAllBytes(candidate));
    }
}
