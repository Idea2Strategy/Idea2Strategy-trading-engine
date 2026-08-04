package com.idea2strategy.trading.common.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileReadinessMarkerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void markerExistsOnlyWhileTheRuntimeIsReady() throws Exception {
        Path markerPath = temporaryDirectory.resolve("nested/runtime.ready");
        Files.createDirectories(markerPath.getParent());
        Files.writeString(markerPath, "stale\n");

        FileReadinessMarker marker = new FileReadinessMarker(markerPath);

        assertFalse(Files.exists(markerPath));

        marker.markReady();

        assertTrue(Files.isRegularFile(markerPath));
        assertEquals("ready\n", Files.readString(markerPath));

        marker.markNotReady();

        assertFalse(Files.exists(markerPath));
    }
}
