package com.idea2strategy.trading.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.trading.market.alpaca.ProviderRightsEvidence;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

public final class FileProviderRightsEvidenceSource implements Supplier<ProviderRightsEvidence> {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path path;

    public FileProviderRightsEvidenceSource(Path path) {
        this.path = Objects.requireNonNull(path, "path");
    }

    @Override
    public ProviderRightsEvidence get() {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(Files.readAllBytes(path));
            return new ProviderRightsEvidence(
                    text(node, "provider"),
                    text(node, "feed"),
                    Instant.parse(text(node, "verifiedAt")),
                    Instant.parse(text(node, "expiresAt")));
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException(
                    "provider rights evidence cannot be read from " + path, exception);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("rights evidence field " + field + " must be present text");
        }
        return value.asText();
    }
}
