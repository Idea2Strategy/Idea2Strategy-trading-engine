package com.idea2strategy.trading.market.alpaca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.idea2strategy.trading.messaging.market.MarketEventType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AlpacaMarketEventNormalizerContractTest {
    private static final UUID AAPL_ID = UUID.fromString("8a35e6b5-cf84-4f63-920d-57c1f1b95df0");

    @Test
    void normalizesTheSameAlpacaInputDeterministically() {
        var normalizer = new AlpacaMarketEventNormalizer(Map.of("AAPL", AAPL_ID));
        var input = quoteInput(Instant.parse("2026-07-31T14:30:00.125Z"), 42L, 0);

        var first = normalizer.normalize(input);
        var replay = normalizer.normalize(input);

        assertEquals(first, replay);
        assertEquals(first.eventId(), replay.eventId());
        assertEquals(1, first.schemaVersion());
        assertEquals(AAPL_ID, first.instrumentId());
        assertEquals("ALPACA", first.provider());
        assertEquals("SIP", first.feed());
        assertEquals(input.occurredAt(), first.occurredAt());
        assertEquals(input.receivedAt(), first.receivedAt());
    }

    @Test
    void givesDuplicateDeliveriesTheSameIdentity() {
        var normalizer = new AlpacaMarketEventNormalizer(Map.of("AAPL", AAPL_ID));

        var first = normalizer.normalize(quoteInput(Instant.parse("2026-07-31T14:30:00.125Z"), 42L, 0));
        var duplicate = normalizer.normalize(quoteInput(Instant.parse("2026-07-31T14:30:00.500Z"), 42L, 0));

        assertEquals(first.eventId(), duplicate.eventId());
        assertNotEquals(first.receivedAt(), duplicate.receivedAt());
    }

    @Test
    void preservesProviderSequenceForOutOfOrderDeliveries() {
        var normalizer = new AlpacaMarketEventNormalizer(Map.of("AAPL", AAPL_ID));

        var newer = normalizer.normalize(quoteInput(Instant.parse("2026-07-31T14:30:01.100Z"), 44L, 0));
        var lateOlder = normalizer.normalize(new AlpacaMarketInput(
                MarketEventType.QUOTE,
                "quote-41",
                "AAPL",
                "sip",
                Instant.parse("2026-07-31T14:29:59Z"),
                Instant.parse("2026-07-31T14:30:01.200Z"),
                41L,
                0,
                quoteValues()));

        assertEquals(44L, newer.sequence());
        assertEquals(41L, lateOlder.sequence());
        assertNotEquals(newer.eventId(), lateOlder.eventId());
    }

    @Test
    void linksARevisionToTheOriginalEvent() {
        var normalizer = new AlpacaMarketEventNormalizer(Map.of("AAPL", AAPL_ID));

        var original = normalizer.normalize(quoteInput(Instant.parse("2026-07-31T14:30:00.125Z"), 42L, 0));
        var correction = normalizer.normalize(quoteInput(Instant.parse("2026-07-31T14:31:00Z"), 42L, 1));

        assertNotEquals(original.eventId(), correction.eventId());
        assertEquals(original.eventId(), correction.correctionOfEventId());
        assertEquals(1, correction.revision());
    }

    @Test
    void rejectsAnInstrumentOutsideTheSupportedUniverse() {
        var normalizer = new AlpacaMarketEventNormalizer(Map.of("AAPL", AAPL_ID));
        var unsupported = new AlpacaMarketInput(
                MarketEventType.TRADE,
                "trade-1",
                "UNKNOWN",
                "sip",
                Instant.parse("2026-07-31T14:30:00Z"),
                Instant.parse("2026-07-31T14:30:00.010Z"),
                1L,
                0,
                Map.of("price", new BigDecimal("10.00"), "size", new BigDecimal("1")));

        var exception = assertThrows(UnsupportedInstrumentException.class, () -> normalizer.normalize(unsupported));

        assertEquals("UNSUPPORTED_INSTRUMENT", exception.reasonCode());
        assertEquals("UNKNOWN", exception.symbol());
    }

    private static AlpacaMarketInput quoteInput(Instant receivedAt, long sequence, int revision) {
        return new AlpacaMarketInput(
                MarketEventType.QUOTE,
                "quote-42",
                "AAPL",
                "sip",
                Instant.parse("2026-07-31T14:30:00Z"),
                receivedAt,
                sequence,
                revision,
                quoteValues());
    }

    private static Map<String, BigDecimal> quoteValues() {
        return Map.of(
                "bidPrice", new BigDecimal("210.10"),
                "bidSize", new BigDecimal("100"),
                "askPrice", new BigDecimal("210.12"),
                "askSize", new BigDecimal("80"));
    }
}
