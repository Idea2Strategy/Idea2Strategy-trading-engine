package com.idea2strategy.trading.common.runtime;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.UUID;

/**
 * A process-local readiness marker for non-HTTP runtimes.
 *
 * <p>The marker is deliberately removed during construction so an ungraceful previous exit cannot
 * make a newly starting process appear ready before its own recovery or provider subscription has
 * completed.
 */
public final class FileReadinessMarker {
    private final Path path;

    public FileReadinessMarker(Path path) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        markNotReady();
    }

    public void markReady() {
        Path parent = path.getParent();
        Path temporary = parent.resolve("." + path.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(parent);
            Files.writeString(temporary, "ready\n");
            try {
                Files.move(
                        temporary,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("unable to publish runtime readiness marker " + path, failure);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // The marker move already determines readiness. A uniquely named temporary file is
                // harmless and can be removed by the next image replacement.
            }
        }
    }

    public void markNotReady() {
        try {
            Files.deleteIfExists(path);
        } catch (IOException failure) {
            throw new IllegalStateException("unable to clear runtime readiness marker " + path, failure);
        }
    }
}
