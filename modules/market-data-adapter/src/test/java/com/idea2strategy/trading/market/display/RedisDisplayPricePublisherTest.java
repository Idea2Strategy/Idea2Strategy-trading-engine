package com.idea2strategy.trading.market.display;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.trading.messaging.market.DisplayPriceUpdate;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandExecutionException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class RedisDisplayPricePublisherTest {
    private static final UUID INSTRUMENT =
            UUID.fromString("68ed5d6c-7472-44d7-a606-bb1d32726d80");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                    .withExposedPorts(6379);

    @Test
    void atomicallyAggregatesIntervalsIntoOneMinuteBarsAndTrimsOldHistory() throws Exception {
        String prefix = "display-" + UUID.randomUUID();
        Instant firstMinute = Instant.parse("2026-08-06T14:31:10Z");
        try (RedisDisplayPricePublisher publisher =
                        RedisDisplayPricePublisher.connect(redisUri(), prefix, 2);
                RedisClient client = RedisClient.create(redisUri());
                var connection = client.connect()) {
            publisher.publish(update(
                    firstMinute, "100", "102", "99", "101", "8", 4, 1));
            publisher.publish(update(
                    firstMinute.plusSeconds(20), "101", "103", "100", "102", "3", 2, 2));

            long firstBucket = minuteBucket(firstMinute);
            String barsKey = "{" + prefix + ":market}:display:bars:1m:" + INSTRUMENT;
            var bar = new ObjectMapper().readTree(
                    connection.sync().hget(barsKey, Long.toString(firstBucket)));
            assertEquals(0, new BigDecimal("100").compareTo(bar.path("open").decimalValue()));
            assertEquals(0, new BigDecimal("103").compareTo(bar.path("high").decimalValue()));
            assertEquals(0, new BigDecimal("99").compareTo(bar.path("low").decimalValue()));
            assertEquals(0, new BigDecimal("102").compareTo(bar.path("close").decimalValue()));
            assertEquals(0, new BigDecimal("11").compareTo(bar.path("volume").decimalValue()));
            assertEquals(6, bar.path("tradeCount").intValue());

            publisher.publish(update(
                    firstMinute.plusSeconds(60), "102", "102", "101", "101", "1", 1, 3));
            publisher.publish(update(
                    firstMinute.plusSeconds(120), "101", "104", "101", "104", "2", 1, 4));

            assertEquals(2, connection.sync().zcard(
                    "{" + prefix + ":market}:display:bar-index:1m:" + INSTRUMENT));
            assertNull(connection.sync().hget(barsKey, Long.toString(firstBucket)));
        }
    }

    @Test
    void rejectsWrongRedisTypesBeforeWritingAnyPartialLatestState() {
        String prefix = "display-" + UUID.randomUUID();
        String keyBase = "{" + prefix + ":market}";
        try (RedisClient client = RedisClient.create(redisUri());
                var connection = client.connect();
                RedisDisplayPricePublisher publisher =
                        RedisDisplayPricePublisher.connect(redisUri(), prefix, 2)) {
            connection.sync().set(
                    keyBase + ":display:bar-index:1m:" + INSTRUMENT, "wrong-type");

            assertThrows(
                    RedisCommandExecutionException.class,
                    () -> publisher.publish(update(
                            Instant.parse("2026-08-06T14:31:10Z"),
                            "100", "100", "100", "100", "1", 1, 1)));
            assertEquals(0, connection.sync().exists(
                    keyBase + ":display:latest:" + INSTRUMENT));
        }
    }

    private static String redisUri() {
        return "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
    }

    private static long minuteBucket(Instant instant) {
        return Math.floorDiv(instant.getEpochSecond(), 60L) * 60L;
    }

    private static DisplayPriceUpdate update(
            Instant occurredAt,
            String open,
            String high,
            String low,
            String close,
            String volume,
            long tradeCount,
            long tradeId) {
        return new DisplayPriceUpdate(
                INSTRUMENT,
                "AAPL",
                new BigDecimal(close),
                BigDecimal.ONE,
                new BigDecimal(open),
                new BigDecimal(high),
                new BigDecimal(low),
                new BigDecimal(close),
                new BigDecimal(volume),
                tradeCount,
                tradeId,
                occurredAt,
                occurredAt.plusMillis(250));
    }
}
