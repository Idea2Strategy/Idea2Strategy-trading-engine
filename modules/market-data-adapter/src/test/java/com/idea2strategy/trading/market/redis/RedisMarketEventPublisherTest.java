package com.idea2strategy.trading.market.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idea2strategy.trading.market.alpaca.AlpacaMarketEventNormalizer;
import com.idea2strategy.trading.market.alpaca.AlpacaMarketInput;
import com.idea2strategy.trading.market.alpaca.MarketEventHandlingResult;
import com.idea2strategy.trading.market.alpaca.MarketEventOrderingProcessor;
import com.idea2strategy.trading.market.availability.MarketDataAvailabilityResult;
import com.idea2strategy.trading.market.availability.MarketDataAvailabilityStatus;
import com.idea2strategy.trading.market.availability.MarketDataDegradationReason;
import com.idea2strategy.trading.messaging.market.MarketEventEnvelope;
import com.idea2strategy.trading.messaging.market.MarketEventType;
import io.lettuce.core.Consumer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.XReadArgs.StreamOffset;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("unchecked")
class RedisMarketEventPublisherTest {
    private static final UUID AAPL_ID = UUID.fromString("8a35e6b5-cf84-4f63-920d-57c1f1b95df0");
    private static final AlpacaMarketEventNormalizer NORMALIZER =
            new AlpacaMarketEventNormalizer(Map.of("AAPL", AAPL_ID));

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    @Test
    void publishesOnceAndAdvancesLatestObservationAtomically() {
        String prefix = prefix();
        try (RedisMarketEventPublisher publisher = RedisMarketEventPublisher.connect(redisUri(), prefix)) {
            MarketEventEnvelope event = event("quote-42", 42, 0, "210.12");
            MarketEventHandlingResult accepted = new MarketEventOrderingProcessor().process(event);

            MarketEventPublishResult first = publisher.publish(accepted);
            MarketEventPublishResult duplicateAfterRestart =
                    publisher.publish(new MarketEventOrderingProcessor().process(event));

            assertEquals(MarketEventPublishStatus.PUBLISHED, first.status());
            assertTrue(first.latestUpdated());
            assertEquals(MarketEventPublishStatus.DUPLICATE, duplicateAfterRestart.status());
            assertEquals(1, publisher.streamLength());
            assertEquals(event, publisher.findLatest(
                    AAPL_ID, MarketEventType.MARKET_EVALUATION_READY).orElseThrow());
        }
    }

    @Test
    void publishesHistoricalCorrectionWithoutMovingLatestObservationBackward() {
        String prefix = prefix();
        MarketEventOrderingProcessor ordering = new MarketEventOrderingProcessor();
        try (RedisMarketEventPublisher publisher = RedisMarketEventPublisher.connect(redisUri(), prefix)) {
            publisher.publish(ordering.process(event("quote-41", 41, 0, "210.10")));
            MarketEventEnvelope latest = event("quote-42", 42, 0, "210.12");
            publisher.publish(ordering.process(latest));

            MarketEventPublishResult correction =
                    publisher.publish(ordering.process(event("quote-41", 41, 1, "210.11")));

            assertEquals(MarketEventPublishStatus.PUBLISHED, correction.status());
            assertFalse(correction.latestUpdated());
            assertEquals(3, publisher.streamLength());
            assertEquals(latest, publisher.findLatest(
                    AAPL_ID, MarketEventType.MARKET_EVALUATION_READY).orElseThrow());
        }
    }

    @Test
    void rejectsWrongTypeBeforeWritingAnyPartOfTheOperation() {
        String prefix = prefix();
        MarketEventEnvelope event = event("quote-42", 42, 0, "210.12");
        try (RedisClient client = RedisClient.create(redisUri());
                var connection = client.connect();
                RedisMarketEventPublisher publisher = RedisMarketEventPublisher.connect(redisUri(), prefix)) {
            connection.sync().set(
                    publisher.latestKey(AAPL_ID, MarketEventType.MARKET_EVALUATION_READY),
                    "wrong-type");

            assertThrows(
                    RedisCommandExecutionException.class,
                    () -> publisher.publish(new MarketEventOrderingProcessor().process(event)));
            assertEquals(0, publisher.streamLength());
            assertEquals(0, connection.sync().scard(publisher.deduplicationKey()));
        }
    }

    @Test
    void measuresConsumerGroupEntryAndObservationLag() {
        String prefix = prefix();
        try (RedisClient client = RedisClient.create(redisUri());
                var connection = client.connect();
                RedisMarketEventPublisher publisher = RedisMarketEventPublisher.connect(redisUri(), prefix)) {
            MarketEventOrderingProcessor ordering = new MarketEventOrderingProcessor();
            publisher.publish(ordering.process(event("quote-41", 41, 0, "210.10")));
            connection.sync().xgroupCreate(
                    StreamOffset.from(publisher.streamKey(), "0-0"),
                    "trading-workers",
                    XGroupCreateArgs.Builder.mkstream());
            publisher.publish(ordering.process(event("quote-42", 42, 0, "210.12")));
            publisher.publish(ordering.process(event("quote-43", 43, 0, "210.13")));
            connection.sync().xreadgroup(
                    Consumer.from("trading-workers", "worker-1"),
                    XReadArgs.Builder.count(1),
                    StreamOffset.lastConsumed(publisher.streamKey()));

            ConsumerLagMeasurement lag = publisher.measureConsumerLag("trading-workers");

            assertEquals(2, lag.entryLag());
            assertEquals(Duration.ofSeconds(2), lag.observationTimeLag());
            assertFalse(lag.lastDeliveredStreamId().isBlank());
        }
    }

    @Test
    void availabilityProjectionRejectsDuplicateAndOutOfOrderUpdates() {
        try (RedisMarketEventPublisher publisher = RedisMarketEventPublisher.connect(redisUri(), prefix())) {
            Instant newest = Instant.parse("2026-08-01T14:31:00Z");
            assertTrue(publisher.publishAvailability(AAPL_ID, 42, newest, available()));
            assertFalse(publisher.publishAvailability(AAPL_ID, 42, newest, degraded()));
            assertFalse(publisher.publishAvailability(
                    AAPL_ID, 41, newest.plusSeconds(1), degraded()));

            var stored = publisher.findAvailability(AAPL_ID).orElseThrow();
            assertEquals(42, stored.marketSequence());
            assertEquals(MarketDataAvailabilityStatus.AVAILABLE, stored.status());
        }
    }

    @Test
    void availabilityProjectionOrdersFractionalInstantsWithinTheSameSecond() {
        try (RedisMarketEventPublisher publisher = RedisMarketEventPublisher.connect(redisUri(), prefix())) {
            Instant wholeSecond = Instant.parse("2026-08-01T14:31:00Z");
            Instant fractionallyLater = Instant.parse("2026-08-01T14:31:00.100Z");

            assertTrue(publisher.publishAvailability(AAPL_ID, 42, wholeSecond, available()));
            assertTrue(publisher.publishAvailability(AAPL_ID, 42, fractionallyLater, degraded()));
            assertFalse(publisher.publishAvailability(
                    AAPL_ID, 42, Instant.parse("2026-08-01T14:31:00.050Z"), available()));

            var stored = publisher.findAvailability(AAPL_ID).orElseThrow();
            assertEquals(fractionallyLater, stored.observedAt());
            assertEquals(MarketDataAvailabilityStatus.DEGRADED, stored.status());
        }
    }

    @Test
    void availabilityProjectionUpgradesAHashWithoutNumericTimeFields() {
        String prefix = prefix();
        try (RedisClient client = RedisClient.create(redisUri());
                var connection = client.connect();
                RedisMarketEventPublisher publisher = RedisMarketEventPublisher.connect(redisUri(), prefix)) {
            connection.sync().hset(publisher.availabilityKey(AAPL_ID), Map.of(
                    "schemaVersion", "1",
                    "instrumentId", AAPL_ID.toString(),
                    "marketSequence", "42",
                    "observedAt", "2026-08-01T14:31:00Z",
                    "status", "AVAILABLE",
                    "evaluationAllowed", "true",
                    "reasons", ""));

            Instant refreshed = Instant.parse("2026-08-01T14:31:00.100Z");
            assertTrue(publisher.publishAvailability(AAPL_ID, 42, refreshed, degraded()));

            Map<String, String> upgraded = connection.sync().hgetall(publisher.availabilityKey(AAPL_ID));
            assertEquals(Long.toString(refreshed.getEpochSecond()), upgraded.get("observedAtEpochSecond"));
            assertEquals(Integer.toString(refreshed.getNano()), upgraded.get("observedAtNano"));
            assertEquals(
                    MarketDataAvailabilityStatus.DEGRADED,
                    publisher.findAvailability(AAPL_ID).orElseThrow().status());
        }
    }

    private static MarketDataAvailabilityResult available() {
        return new MarketDataAvailabilityResult(
                MarketDataAvailabilityStatus.AVAILABLE, true, true, Set.of(), List.of());
    }

    private static MarketDataAvailabilityResult degraded() {
        return new MarketDataAvailabilityResult(
                MarketDataAvailabilityStatus.DEGRADED,
                false,
                false,
                Set.of(MarketDataDegradationReason.STREAM_STALE),
                List.of());
    }

    private static String redisUri() {
        return "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
    }

    private static String prefix() {
        return "test:" + UUID.randomUUID();
    }

    private static MarketEventEnvelope event(
            String providerEventId,
            long sequence,
            int revision,
            String price) {
        Instant occurredAt = Instant.parse("2026-08-01T14:30:00Z").plusSeconds(sequence);
        return NORMALIZER.normalize(new AlpacaMarketInput(
                MarketEventType.MARKET_EVALUATION_READY,
                providerEventId,
                "AAPL",
                "sip",
                occurredAt,
                occurredAt.plusMillis(10),
                sequence,
                revision,
                Map.of("price", new BigDecimal(price))));
    }
}
