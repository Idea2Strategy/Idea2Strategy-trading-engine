package com.idea2strategy.trading.messaging.contract.v1;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradingEnvelopeV1Test {

    @Test
    void rejectsInvalidRequiredMetadataAndNoncanonicalDecimals() {
        assertThatThrownBy(() -> envelopeWithSchemaVersion(" "))
            .hasMessageContaining("schemaVersion");
        assertThatThrownBy(() -> envelopeWithAggregateVersion(0))
            .hasMessageContaining("aggregateVersion");
        assertThatThrownBy(() -> envelopeWithPayload(null))
            .hasMessageContaining("payload");
        assertThatThrownBy(() -> new DecimalValueV1("1e3"))
            .hasMessageContaining("decimal");
        assertThatThrownBy(() -> new DecimalValueV1("1.20"))
            .hasMessageContaining("canonical");
    }

    private TradingEnvelopeV1<String> envelopeWithSchemaVersion(String schemaVersion) {
        return envelope(schemaVersion, 1, "payload");
    }

    private TradingEnvelopeV1<String> envelopeWithAggregateVersion(long aggregateVersion) {
        return envelope("trading-envelope.v1", aggregateVersion, "payload");
    }

    private TradingEnvelopeV1<String> envelopeWithPayload(String payload) {
        return envelope("trading-envelope.v1", 1, payload);
    }

    private TradingEnvelopeV1<String> envelope(String schemaVersion, long aggregateVersion, String payload) {
        return new TradingEnvelopeV1<>(
            schemaVersion,
            "order.partially-filled",
            UUID.fromString("81111111-1111-1111-1111-111111111111"),
            Instant.parse("2026-07-31T00:00:00Z"),
            "trading-engine",
            UUID.fromString("82222222-2222-2222-2222-222222222222"),
            UUID.fromString("83333333-3333-3333-3333-333333333333"),
            "partial-fill-1",
            UUID.fromString("84444444-4444-4444-4444-444444444444"),
            aggregateVersion,
            payload
        );
    }
}
