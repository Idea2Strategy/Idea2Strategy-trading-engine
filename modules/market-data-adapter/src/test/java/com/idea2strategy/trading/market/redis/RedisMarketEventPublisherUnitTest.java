package com.idea2strategy.trading.market.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.idea2strategy.trading.market.alpaca.AlpacaMarketEventNormalizer;
import com.idea2strategy.trading.market.alpaca.AlpacaMarketInput;
import com.idea2strategy.trading.market.alpaca.MarketEventHandlingResult;
import com.idea2strategy.trading.market.alpaca.MarketEventOrderingProcessor;
import com.idea2strategy.trading.market.availability.MarketDataAvailabilityResult;
import com.idea2strategy.trading.market.availability.MarketDataAvailabilityStatus;
import com.idea2strategy.trading.messaging.market.MarketEventEnvelope;
import com.idea2strategy.trading.messaging.market.MarketEventType;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RedisMarketEventPublisherUnitTest {
    private static final UUID AAPL_ID = UUID.fromString("8a35e6b5-cf84-4f63-920d-57c1f1b95df0");
    private static final AlpacaMarketEventNormalizer NORMALIZER =
            new AlpacaMarketEventNormalizer(Map.of("AAPL", AAPL_ID));

    @Mock
    private RedisCommands<String, String> commands;

    private RedisMarketEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new RedisMarketEventPublisher(commands, "unit-test");
    }

    @Test
    void skipsRejectedOrderingResultsWithoutTouchingRedis() {
        MarketEventOrderingProcessor ordering = new MarketEventOrderingProcessor();
        MarketEventEnvelope event = event("quote-42", 42, 0, "210.12");
        ordering.process(event);

        MarketEventPublishResult result = publisher.publish(ordering.process(event));

        assertEquals(MarketEventPublishStatus.SKIPPED, result.status());
        verifyNoInteractions(commands);
    }

    @Test
    @SuppressWarnings("unchecked")
    void passesStableIdentityAndOrderingFieldsToOneAtomicScript() {
        doReturn(List.of(1L, "1722510000000-0", 1L))
                .when(commands)
                .eval(anyString(), eq(ScriptOutputType.MULTI), any(String[].class), any(String[].class));
        MarketEventEnvelope event = event("quote-42", 42, 0, "210.12");

        MarketEventPublishResult result =
                publisher.publish(new MarketEventOrderingProcessor().process(event));

        ArgumentCaptor<String[]> keys = ArgumentCaptor.forClass(String[].class);
        ArgumentCaptor<String[]> arguments = ArgumentCaptor.forClass(String[].class);
        verify(commands).eval(anyString(), eq(ScriptOutputType.MULTI), keys.capture(), arguments.capture());
        assertEquals(MarketEventPublishStatus.PUBLISHED, result.status());
        assertEquals("1722510000000-0", result.streamEntryId());
        assertEquals(List.of(
                        "{unit-test:market}:events",
                        "{unit-test:market}:latest:" + AAPL_ID + ":QUOTE",
                        "{unit-test:market}:seen:v2",
                        "{unit-test:market}:bars:" + AAPL_ID + ":none"),
                List.of(keys.getValue()));
        assertEquals(event.eventId(), arguments.getValue()[0]);
        assertEquals("42", arguments.getValue()[9]);
        assertEquals("0", arguments.getValue()[10]);
        assertEquals("1", arguments.getValue()[13]);
        assertEquals("1000000", arguments.getValue()[18]);
    }

    @Test
    @SuppressWarnings("unchecked")
    void retainsThirtyMinuteStrategyBarsWithoutDisplayFanout() {
        doReturn(List.of(1L, "1722510000000-0", 1L))
                .when(commands)
                .eval(anyString(), eq(ScriptOutputType.MULTI), any(String[].class), any(String[].class));
        MarketEventEnvelope bar = thirtyMinuteBar(42, "210.12");

        publisher.publish(new MarketEventOrderingProcessor().process(bar));

        ArgumentCaptor<String[]> keys = ArgumentCaptor.forClass(String[].class);
        ArgumentCaptor<String[]> arguments = ArgumentCaptor.forClass(String[].class);
        verify(commands).eval(anyString(), eq(ScriptOutputType.MULTI), keys.capture(), arguments.capture());
        assertEquals("{unit-test:market}:bars:" + AAPL_ID + ":30m", keys.getValue()[3]);
        assertEquals("390", arguments.getValue()[14]);
        assertEquals(true, arguments.getValue()[15].contains("\"instrumentId\":\"" + AAPL_ID + "\""));
        assertEquals(true, arguments.getValue()[15].contains("\"close\":210.12"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void routesOnlyEvaluationReadyEventsToTheWorkerFacingStream() {
        doReturn(List.of(1L, "1722510000000-0", 1L))
                .when(commands)
                .eval(anyString(), eq(ScriptOutputType.MULTI), any(String[].class), any(String[].class));
        Instant boundary = Instant.parse("2026-08-01T15:00:00Z");
        MarketEventEnvelope evaluation = new MarketEventEnvelope(
                "evaluation-1", 2, AAPL_ID, "ALPACA", "SIP_30MIN_REST",
                MarketEventType.MARKET_EVALUATION_READY, "evaluation-boundary-1",
                boundary, boundary.plusSeconds(2), boundary.getEpochSecond() / 1800, 0, null,
                Map.of("close", new BigDecimal("210.12"), "closed30m", BigDecimal.ONE));

        publisher.publish(new MarketEventOrderingProcessor().process(evaluation));

        ArgumentCaptor<String[]> keys = ArgumentCaptor.forClass(String[].class);
        verify(commands).eval(anyString(), eq(ScriptOutputType.MULTI), keys.capture(), any(String[].class));
        assertEquals("{unit-test:market}:strategy:evaluation-ready:v1", keys.getValue()[0]);
    }

    @Test
    void reconstructsTheLatestObservationWithoutChangingDecimalMeaning() {
        when(commands.hgetall("{unit-test:market}:latest:" + AAPL_ID + ":QUOTE"))
                .thenReturn(Map.ofEntries(
                        Map.entry("eventId", "event-42"),
                        Map.entry("schemaVersion", "1"),
                        Map.entry("instrumentId", AAPL_ID.toString()),
                        Map.entry("provider", "alpaca"),
                        Map.entry("feed", "sip"),
                        Map.entry("eventType", "QUOTE"),
                        Map.entry("providerEventId", "quote-42"),
                        Map.entry("occurredAt", "2026-08-01T14:30:42Z"),
                        Map.entry("receivedAt", "2026-08-01T14:30:42.010Z"),
                        Map.entry("sequence", "42"),
                        Map.entry("revision", "0"),
                        Map.entry("correctionOfEventId", ""),
                        Map.entry("values", "{\"price\":210.1200}")));

        MarketEventEnvelope latest = publisher.findLatest(AAPL_ID, MarketEventType.QUOTE).orElseThrow();

        assertEquals(42, latest.sequence());
        assertEquals(new BigDecimal("210.1200"), latest.values().get("price"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishesAvailabilityThroughTheMonotonicAtomicProjection() {
        doReturn(1L).when(commands).eval(
                anyString(), eq(ScriptOutputType.INTEGER), any(String[].class), any(String[].class));
        Instant observedAt = Instant.parse("2026-08-01T14:31:00Z");

        boolean updated = publisher.publishAvailability(
                AAPL_ID,
                42,
                observedAt,
                new MarketDataAvailabilityResult(
                        MarketDataAvailabilityStatus.AVAILABLE,
                        true,
                        true,
                        Set.of(),
                        List.of()));

        ArgumentCaptor<String[]> keys = ArgumentCaptor.forClass(String[].class);
        ArgumentCaptor<String[]> arguments = ArgumentCaptor.forClass(String[].class);
        verify(commands).eval(anyString(), eq(ScriptOutputType.INTEGER), keys.capture(), arguments.capture());
        assertEquals(true, updated);
        assertEquals(List.of("{unit-test:market}:availability:" + AAPL_ID), List.of(keys.getValue()));
        assertEquals("42", arguments.getValue()[2]);
        assertEquals(observedAt.toString(), arguments.getValue()[3]);
        assertEquals(Long.toString(observedAt.getEpochSecond()), arguments.getValue()[4]);
        assertEquals(Integer.toString(observedAt.getNano()), arguments.getValue()[5]);
        assertEquals("AVAILABLE", arguments.getValue()[6]);
        assertEquals("true", arguments.getValue()[7]);
    }

    @Test
    void readsTheGatewayAvailabilityResultByInstrument() {
        when(commands.hgetall("{unit-test:market}:availability:" + AAPL_ID)).thenReturn(Map.of(
                "schemaVersion", "1",
                "instrumentId", AAPL_ID.toString(),
                "marketSequence", "42",
                "observedAt", "2026-08-01T14:31:00Z",
                "status", "AVAILABLE",
                "evaluationAllowed", "true",
                "reasons", ""));

        var projection = publisher.findAvailability(AAPL_ID).orElseThrow();

        assertEquals(AAPL_ID, projection.instrumentId());
        assertEquals(42, projection.marketSequence());
        assertEquals(true, projection.evaluationAllowed());
    }

    @Test
    @SuppressWarnings("unchecked")
    void convertsRedisGroupStateIntoNonNegativeLag() {
        doReturn(List.of(
                        "2",
                        "1722510000000-0",
                        "2026-08-01T14:30:41Z",
                        "2026-08-01T14:30:43Z"))
                .when(commands)
                .eval(anyString(), eq(ScriptOutputType.MULTI), any(String[].class), any(String[].class));

        ConsumerLagMeasurement lag = publisher.measureConsumerLag("trading-workers");

        assertEquals(2, lag.entryLag());
        assertEquals(Duration.ofSeconds(2), lag.observationTimeLag());
        assertEquals("1722510000000-0", lag.lastDeliveredStreamId());
    }

    @Test
    void rejectsInvalidResultAndLagStates() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MarketEventPublishResult(MarketEventPublishStatus.DUPLICATE, "1-0", false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConsumerLagMeasurement(-1, Duration.ZERO, "0-0"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConsumerLagMeasurement(0, Duration.ofSeconds(-1), "0-0"));
    }

    private static MarketEventEnvelope event(
            String providerEventId,
            long sequence,
            int revision,
            String price) {
        Instant occurredAt = Instant.parse("2026-08-01T14:30:00Z").plusSeconds(sequence);
        return NORMALIZER.normalize(new AlpacaMarketInput(
                MarketEventType.QUOTE,
                providerEventId,
                "AAPL",
                "sip",
                occurredAt,
                occurredAt.plusMillis(10),
                sequence,
                revision,
                Map.of("price", new BigDecimal(price))));
    }

    private static MarketEventEnvelope thirtyMinuteBar(long sequence, String close) {
        Instant occurredAt = Instant.parse("2026-08-01T14:30:00Z").plusSeconds(sequence * 1800);
        return NORMALIZER.normalize(new AlpacaMarketInput(
                MarketEventType.BAR_30M,
                "bar-" + sequence,
                "AAPL",
                "sip",
                occurredAt,
                occurredAt.plusMillis(10),
                sequence,
                0,
                Map.of(
                        "open", new BigDecimal("210.00"),
                        "high", new BigDecimal("210.20"),
                        "low", new BigDecimal("209.90"),
                        "close", new BigDecimal(close),
                        "volume", new BigDecimal("2500"))));
    }
}
