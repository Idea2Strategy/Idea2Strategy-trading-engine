package com.idea2strategy.trading.common.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MaterializationReceiptTest {
    @TempDir
    Path root;

    @Test
    void verifiesVersionedSourcesAndEveryMaterializedFile() throws Exception {
        Path mapping = Files.writeString(root.resolve("instruments.json"), "{}");
        Path rights = Files.writeString(root.resolve("rights.json"), "{}");
        Path receipt = receipt(mapping, rights);

        assertDoesNotThrow(() -> MaterializationReceipt.verify(
                receipt, Map.of("instrument-mapping", mapping, "provider-rights", rights)));
    }

    @Test
    void failsClosedWhenAFileChangesAfterMaterialization() throws Exception {
        Path mapping = Files.writeString(root.resolve("instruments.json"), "{}");
        Path rights = Files.writeString(root.resolve("rights.json"), "{}");
        Path receipt = receipt(mapping, rights);
        Files.writeString(mapping, "{\"SPY\":\"changed\"}");

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> MaterializationReceipt.verify(
                        receipt, Map.of("instrument-mapping", mapping, "provider-rights", rights)));

        assertTrue(failure.getMessage().contains("checksum"));
    }

    @Test
    void failsClosedWithoutAnImmutableS3Version() throws Exception {
        Path mapping = Files.writeString(root.resolve("instruments.json"), "{}");
        Path receipt = root.resolve("receipt.properties");
        Files.writeString(receipt, """
                contract=i2s.materialization-receipt
                schema-version=1
                artifact-count=1
                artifact.0.id=instrument-mapping
                artifact.0.source-bucket=runtime-bucket
                artifact.0.source-key=trading/instruments.json
                artifact.0.source-version-id=
                artifact.0.sha256=%s
                artifact.0.local-path=%s
                """.formatted(sha256(mapping), portable(mapping)));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> MaterializationReceipt.verify(receipt, Map.of("instrument-mapping", mapping)));

        assertTrue(failure.getMessage().contains("source-version-id"));
    }

    @Test
    void failsClosedWhenARequiredArtifactIsNotReceipted() throws Exception {
        Path mapping = Files.writeString(root.resolve("instruments.json"), "{}");
        Path rights = Files.writeString(root.resolve("rights.json"), "{}");
        Path receipt = receipt(mapping);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> MaterializationReceipt.verify(
                        receipt, Map.of("instrument-mapping", mapping, "provider-rights", rights)));

        assertTrue(failure.getMessage().contains("provider-rights"));
    }

    private Path receipt(Path... files) throws Exception {
        StringBuilder content = new StringBuilder()
                .append("contract=i2s.materialization-receipt\n")
                .append("schema-version=1\n")
                .append("artifact-count=").append(files.length).append('\n');
        for (int index = 0; index < files.length; index++) {
            Path file = files[index];
            String id = index == 0 ? "instrument-mapping" : "provider-rights";
            content.append("artifact.").append(index).append(".id=").append(id).append('\n')
                    .append("artifact.").append(index).append(".source-bucket=runtime-bucket\n")
                    .append("artifact.").append(index).append(".source-key=trading/").append(file.getFileName()).append('\n')
                    .append("artifact.").append(index).append(".source-version-id=v").append(index + 1).append('\n')
                    .append("artifact.").append(index).append(".sha256=").append(sha256(file)).append('\n')
                    .append("artifact.").append(index).append(".local-path=").append(portable(file)).append('\n');
        }
        return Files.writeString(root.resolve("receipt.properties"), content);
    }

    private static String portable(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
