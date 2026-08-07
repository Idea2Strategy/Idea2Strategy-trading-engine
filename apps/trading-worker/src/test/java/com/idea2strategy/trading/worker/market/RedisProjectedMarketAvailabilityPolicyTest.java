package com.idea2strategy.trading.worker.market;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.idea2strategy.trading.messaging.market.MarketEventEnvelope;
import com.idea2strategy.trading.messaging.market.MarketEventType;
import io.lettuce.core.api.sync.RedisCommands;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RedisProjectedMarketAvailabilityPolicyTest {
    private static final UUID INSTRUMENT = UUID.fromString("8a35e6b5-cf84-4f63-920d-57c1f1b95df0");
    private static final Instant NOW = Instant.parse("2026-08-01T14:31:30Z");
    private static final String KEY = "{test:market}:availability:" + INSTRUMENT;

    @Mock RedisCommands<String, String> commands;
    private RedisProjectedMarketAvailabilityPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new RedisProjectedMarketAvailabilityPolicy(
                commands, "test", Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(2));
    }

    @Test
    void permitsAHealthyProjectionAtOrAheadOfTheEvent() {
        when(commands.hgetall(KEY)).thenReturn(projection("42", "2026-08-01T14:31:00Z", "AVAILABLE", "true", ""));

        assertTrue(policy.permits(event(42)));
    }

    @Test
    void failsClosedForMissingMalformedStaleDegradedAndOutOfOrderProjections() {
        when(commands.hgetall(KEY))
                .thenReturn(Map.of())
                .thenReturn(Map.of("schemaVersion", "broken"))
                .thenReturn(projection("42", "2026-08-01T14:20:00Z", "AVAILABLE", "true", ""))
                .thenReturn(projection("42", "2026-08-01T14:31:00Z", "DEGRADED", "false", "STREAM_STALE"))
                .thenReturn(projection("41", "2026-08-01T14:31:00Z", "AVAILABLE", "true", ""));

        assertFalse(policy.permits(event(42)));
        assertFalse(policy.permits(event(42)));
        assertFalse(policy.permits(event(42)));
        assertFalse(policy.permits(event(42)));
        assertFalse(policy.permits(event(42)));
    }

    private static Map<String, String> projection(
            String sequence, String observedAt, String status, String allowed, String reasons) {
        Map<String, String> fields = new HashMap<>();
        fields.put("schemaVersion", "1");
        fields.put("instrumentId", INSTRUMENT.toString());
        fields.put("marketSequence", sequence);
        fields.put("observedAt", observedAt);
        fields.put("status", status);
        fields.put("evaluationAllowed", allowed);
        fields.put("reasons", reasons);
        return fields;
    }

    private static MarketEventEnvelope event(long sequence) {
        return new MarketEventEnvelope(
                "event-" + sequence,
                1,
                INSTRUMENT,
                "ALPACA",
                "SIP",
                MarketEventType.MARKET_EVALUATION_READY,
                "bar-" + sequence,
                NOW.minusSeconds(30),
                NOW.minusSeconds(29),
                sequence,
                0,
                null,
                Map.of("close", new BigDecimal("210.12")));
    }
}
