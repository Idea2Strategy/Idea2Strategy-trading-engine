package com.idea2strategy.trading.market.alpaca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idea2strategy.trading.messaging.market.MarketEventEnvelope;
import com.idea2strategy.trading.messaging.market.MarketEventType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MarketEventOrderingProcessorTest {
    private static final UUID AAPL_ID = UUID.fromString("8a35e6b5-cf84-4f63-920d-57c1f1b95df0");
    private static final UUID MSFT_ID = UUID.fromString("d573109b-5be0-4f38-944a-4d83366ce8c7");
    private static final AlpacaMarketEventNormalizer NORMALIZER =
            new AlpacaMarketEventNormalizer(Map.of("AAPL", AAPL_ID, "MSFT", MSFT_ID));

    @Test
    void keepsLatestSequenceWhenDuplicateAndOlderOriginalEventsArrive() {
        MarketEventOrderingProcessor processor = new MarketEventOrderingProcessor();
        MarketEventEnvelope sequence42 = event("quote-42", MarketEventType.QUOTE, 42, 0, "210.12");
        MarketEventEnvelope replay = event("quote-42", MarketEventType.QUOTE, 42, 0, "210.12");
        MarketEventEnvelope lateSequence41 = event("quote-41", MarketEventType.QUOTE, 41, 0, "210.10");

        MarketEventHandlingResult applied = processor.process(sequence42);
        MarketEventHandlingResult duplicate = processor.process(replay);
        MarketEventHandlingResult outOfOrder = processor.process(lateSequence41);

        assertEquals(MarketEventHandlingStatus.APPLIED, applied.status());
        assertEquals(MarketEventHandlingStatus.DUPLICATE, duplicate.status());
        assertEquals(MarketEventHandlingStatus.OUT_OF_ORDER, outOfOrder.status());
        assertEquals(42, outOfOrder.latestAppliedSequence());
        assertFalse(duplicate.shouldPublish());
        assertTrue(outOfOrder.shouldPublish());
        assertFalse(outOfOrder.shouldUpdateLatestValue());
    }

    @Test
    void appliesANewerCorrectionExactlyOnceAndKeepsItsOriginalLink() {
        MarketEventOrderingProcessor processor = new MarketEventOrderingProcessor();
        MarketEventEnvelope original = event("quote-42", MarketEventType.QUOTE, 42, 0, "210.12");
        MarketEventEnvelope correction = event("quote-42", MarketEventType.QUOTE, 42, 1, "211.00");

        processor.process(original);
        MarketEventHandlingResult appliedCorrection = processor.process(correction);
        MarketEventHandlingResult replay = processor.process(correction);

        assertEquals(original.eventId(), correction.correctionOfEventId());
        assertEquals(MarketEventHandlingStatus.CORRECTION_APPLIED, appliedCorrection.status());
        assertTrue(appliedCorrection.shouldPublish());
        assertTrue(appliedCorrection.shouldUpdateLatestValue());
        assertEquals(MarketEventHandlingStatus.DUPLICATE, replay.status());
        assertFalse(replay.shouldPublish());
    }

    @Test
    void ignoresACorrectionOlderThanTheAppliedRevision() {
        MarketEventOrderingProcessor processor = new MarketEventOrderingProcessor();
        MarketEventEnvelope original = event("quote-42", MarketEventType.QUOTE, 42, 0, "210.12");
        MarketEventEnvelope revisionTwo = event("quote-42", MarketEventType.QUOTE, 42, 2, "212.00");
        MarketEventEnvelope lateRevisionOne = event("quote-42", MarketEventType.QUOTE, 42, 1, "211.00");

        processor.process(original);
        processor.process(revisionTwo);
        MarketEventHandlingResult stale = processor.process(lateRevisionOne);

        assertEquals(MarketEventHandlingStatus.STALE_CORRECTION, stale.status());
        assertFalse(stale.shouldPublish());
        assertFalse(stale.shouldUpdateLatestValue());
    }

    @Test
    void retriesAnOrphanCorrectionAfterItsOriginalArrives() {
        MarketEventOrderingProcessor processor = new MarketEventOrderingProcessor();
        MarketEventEnvelope original = event("quote-42", MarketEventType.QUOTE, 42, 0, "210.12");
        MarketEventEnvelope correction = event("quote-42", MarketEventType.QUOTE, 42, 1, "211.00");

        MarketEventHandlingResult orphan = processor.process(correction);
        processor.process(original);
        MarketEventHandlingResult retried = processor.process(correction);

        assertEquals(MarketEventHandlingStatus.ORPHAN_CORRECTION, orphan.status());
        assertFalse(orphan.shouldPublish());
        assertEquals(MarketEventHandlingStatus.CORRECTION_APPLIED, retried.status());
    }

    @Test
    void isolatesOrderingByInstrumentAndEventType() {
        MarketEventOrderingProcessor processor = new MarketEventOrderingProcessor();

        MarketEventHandlingResult aaplQuote = processor.process(
                event("aapl-quote-10", "AAPL", MarketEventType.QUOTE, 10, 0, "210.00"));
        MarketEventHandlingResult msftQuote = processor.process(
                event("msft-quote-1", "MSFT", MarketEventType.QUOTE, 1, 0, "420.00"));
        MarketEventHandlingResult aaplTrade = processor.process(
                event("aapl-trade-1", "AAPL", MarketEventType.TRADE, 1, 0, "210.01"));

        assertEquals(MarketEventHandlingStatus.APPLIED, aaplQuote.status());
        assertEquals(MarketEventHandlingStatus.APPLIED, msftQuote.status());
        assertEquals(MarketEventHandlingStatus.APPLIED, aaplTrade.status());
        assertEquals(1, msftQuote.latestAppliedSequence());
        assertEquals(1, aaplTrade.latestAppliedSequence());
    }

    @Test
    void blocksDifferentOriginalEventsThatClaimTheSameSequence() {
        MarketEventOrderingProcessor processor = new MarketEventOrderingProcessor();
        processor.process(event("quote-42-a", MarketEventType.QUOTE, 42, 0, "210.12"));

        MarketEventHandlingResult conflict =
                processor.process(event("quote-42-b", MarketEventType.QUOTE, 42, 0, "210.13"));

        assertEquals(MarketEventHandlingStatus.SEQUENCE_CONFLICT, conflict.status());
        assertFalse(conflict.shouldPublish());
        assertEquals(42, conflict.latestAppliedSequence());
    }

    @Test
    void historicalCorrectionDoesNotReplaceTheLatestCachedValue() {
        MarketEventOrderingProcessor processor = new MarketEventOrderingProcessor();
        MarketEventEnvelope original41 = event("quote-41", MarketEventType.QUOTE, 41, 0, "210.10");
        MarketEventEnvelope correction41 = event("quote-41", MarketEventType.QUOTE, 41, 1, "210.11");
        processor.process(original41);
        processor.process(event("quote-42", MarketEventType.QUOTE, 42, 0, "210.12"));

        MarketEventHandlingResult correction = processor.process(correction41);

        assertEquals(MarketEventHandlingStatus.CORRECTION_APPLIED, correction.status());
        assertTrue(correction.shouldPublish());
        assertFalse(correction.shouldUpdateLatestValue());
        assertEquals(42, correction.latestAppliedSequence());
    }

    private static MarketEventEnvelope event(
            String providerEventId,
            MarketEventType eventType,
            long sequence,
            int revision,
            String price) {
        return event(providerEventId, "AAPL", eventType, sequence, revision, price);
    }

    private static MarketEventEnvelope event(
            String providerEventId,
            String symbol,
            MarketEventType eventType,
            long sequence,
            int revision,
            String price) {
        Instant occurredAt = Instant.parse("2026-08-01T14:30:00Z").plusSeconds(sequence);
        return NORMALIZER.normalize(new AlpacaMarketInput(
                eventType,
                providerEventId,
                symbol,
                "sip",
                occurredAt,
                occurredAt.plusMillis(10),
                sequence,
                revision,
                Map.of("price", new BigDecimal(price))));
    }
}
