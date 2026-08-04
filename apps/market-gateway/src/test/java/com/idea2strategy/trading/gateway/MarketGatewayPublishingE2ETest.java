package com.idea2strategy.trading.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idea2strategy.trading.market.alpaca.ProviderRightsUnavailableException;
import com.idea2strategy.trading.market.availability.MarketDataAvailabilityStatus;
import com.idea2strategy.trading.market.redis.RedisMarketEventPublisher;
import com.idea2strategy.trading.messaging.market.MarketEventEnvelope;
import com.idea2strategy.trading.messaging.market.MarketEventType;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class MarketGatewayPublishingE2ETest {
    private static final UUID AAPL_ID = UUID.fromString("8a35e6b5-cf84-4f63-920d-57c1f1b95df0");
    private static final String BAR_FRAME =
            "[{\"T\":\"b\",\"S\":\"AAPL\",\"o\":210.10,\"h\":210.25,\"l\":210.05,"
                    + "\"c\":210.20,\"v\":2500,\"t\":\"2026-07-31T14:30:00Z\"}]";

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    @TempDir
    Path configDir;

    @Test
    void publishesRealAlpacaBarsToRedisAndSurvivesADroppedConnection() throws Exception {
        FakeAlpacaSipServer server = new FakeAlpacaSipServer(1);
        Path mapping = mapping();
        Path readinessFile = readinessFile(mapping);
        server.startAndAwait();
        try {
            try (ConfigurableApplicationContext context = gateway(server.port(), validRights(), mapping)) {
                RedisMarketEventPublisher publisher = context.getBean(RedisMarketEventPublisher.class);

                waitUntil(() -> publisher.streamLength() >= 1, Duration.ofSeconds(30));
                waitUntil(() -> Files.isRegularFile(readinessFile), Duration.ofSeconds(10));
                Thread.sleep(500);

                assertEquals(1, publisher.streamLength());
                MarketEventEnvelope latest =
                        publisher.findLatest(AAPL_ID, MarketEventType.BAR_1M).orElseThrow();
                assertEquals("bar-20260731T143000Z", latest.providerEventId());
                assertEquals("ALPACA", latest.provider());
                assertEquals("SIP", latest.feed());
                assertEquals(Instant.parse("2026-07-31T14:30:00Z"), latest.occurredAt());
                assertEquals(Instant.parse("2026-07-31T14:30:00Z").getEpochSecond() / 60, latest.sequence());
                assertEquals(
                        Map.of(
                                "open", new BigDecimal("210.10"),
                                "high", new BigDecimal("210.25"),
                                "low", new BigDecimal("210.05"),
                                "close", new BigDecimal("210.20"),
                                "volume", new BigDecimal("2500")),
                        latest.values());
                var availability = publisher.findAvailability(AAPL_ID).orElseThrow();
                assertEquals(latest.sequence(), availability.marketSequence());
                assertEquals(MarketDataAvailabilityStatus.AVAILABLE, availability.status());
                assertTrue(availability.evaluationAllowed());

                assertEquals(2, server.connections.get());
                assertTrue(server.received.stream().anyMatch(frame ->
                        frame.contains("\"action\":\"auth\"") && frame.contains("\"key\":\"test-key\"")));
                assertTrue(server.received.stream().anyMatch(frame ->
                        frame.contains("\"action\":\"subscribe\"") && frame.contains("\"bars\":[\"AAPL\"]")));
            }
            assertFalse(Files.exists(readinessFile));
        } finally {
            server.stop();
        }
    }

    @Test
    void expiredRightsEvidenceBlocksStartup() throws IOException {
        Path rights = rightsFile(Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600));
        Exception failure = assertThrows(
                Exception.class, () -> gateway(9, rights, mapping()).close());
        assertTrue(hasCause(failure, ProviderRightsUnavailableException.class));
    }

    @Test
    void missingInstrumentMappingBlocksStartup() throws IOException {
        Exception failure = assertThrows(
                Exception.class,
                () -> gateway(9, validRights(), configDir.resolve("missing-mapping.json")).close());
        assertTrue(hasCauseMessage(failure, "instrument mapping file is missing"));
    }

    @Test
    void missingCredentialsBlockStartup() throws IOException {
        Path rights = validRights();
        Path mapping = mapping();
        Exception failure = assertThrows(Exception.class, () -> new SpringApplicationBuilder(
                        MarketGatewayApplication.class)
                .properties(baseProperties(9, rights, mapping))
                .run()
                .close());
        assertTrue(hasCauseMessage(failure, "ALPACA_API_KEY"));
    }

    private ConfigurableApplicationContext gateway(int port, Path rights, Path mapping) {
        String[] properties = baseProperties(port, rights, mapping);
        String[] withCredentials = new String[properties.length + 2];
        System.arraycopy(properties, 0, withCredentials, 0, properties.length);
        withCredentials[properties.length] = "ALPACA_API_KEY=test-key";
        withCredentials[properties.length + 1] = "ALPACA_API_SECRET=test-secret";
        return new SpringApplicationBuilder(MarketGatewayApplication.class)
                .properties(withCredentials)
                .run();
    }

    private static String[] baseProperties(int port, Path rights, Path mapping) {
        return new String[] {
            "market-gateway.redis-uri=redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379),
            "market-gateway.redis-key-prefix=test:" + UUID.randomUUID(),
            "market-gateway.instrument-mapping-path=" + mapping,
            "market-gateway.rights-evidence-path=" + rights,
            "i2s.readiness-file=" + readinessFile(mapping),
            "market-gateway.alpaca-endpoint=ws://127.0.0.1:" + port,
            "market-gateway.reconnect-initial-delay=PT0.2S",
            "market-gateway.reconnect-max-delay=PT1S",
        };
    }

    private static Path readinessFile(Path mapping) {
        return mapping.resolveSibling(mapping.getFileName() + ".ready");
    }

    private Path validRights() throws IOException {
        return rightsFile(Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600));
    }

    private Path rightsFile(Instant verifiedAt, Instant expiresAt) throws IOException {
        Path path = configDir.resolve("alpaca-sip-rights-" + UUID.randomUUID() + ".json");
        Files.writeString(path, "{\"provider\":\"alpaca\",\"feed\":\"sip\","
                + "\"verifiedAt\":\"" + verifiedAt + "\",\"expiresAt\":\"" + expiresAt + "\"}");
        return path;
    }

    private Path mapping() throws IOException {
        Path path = configDir.resolve("instruments.json");
        Files.writeString(path, "{\"AAPL\":\"" + AAPL_ID + "\"}");
        return path;
    }

    private static void waitUntil(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("condition was not met within " + timeout);
            }
            Thread.sleep(50);
        }
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCauseMessage(Throwable failure, String fragment) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static final class FakeAlpacaSipServer extends WebSocketServer {
        private final CountDownLatch started = new CountDownLatch(1);
        private final List<String> received = new CopyOnWriteArrayList<>();
        private final AtomicInteger connections = new AtomicInteger();
        private final int connectionsToDropOnOpen;

        private FakeAlpacaSipServer(int connectionsToDropOnOpen) {
            super(new InetSocketAddress("127.0.0.1", 0));
            this.connectionsToDropOnOpen = connectionsToDropOnOpen;
            setReuseAddr(true);
        }

        private void startAndAwait() throws InterruptedException {
            start();
            if (!started.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("fake Alpaca SIP server did not start");
            }
        }

        private int port() {
            return getPort();
        }

        @Override
        public void onStart() {
            started.countDown();
        }

        @Override
        public void onOpen(WebSocket connection, ClientHandshake handshake) {
            if (connections.incrementAndGet() <= connectionsToDropOnOpen) {
                connection.close(1011, "dropped to force a reconnect");
                return;
            }
            connection.send("[{\"T\":\"success\",\"msg\":\"connected\"}]");
        }

        @Override
        public void onMessage(WebSocket connection, String message) {
            received.add(message);
            if (message.contains("\"action\":\"auth\"")) {
                connection.send("[{\"T\":\"success\",\"msg\":\"authenticated\"}]");
            } else if (message.contains("\"action\":\"subscribe\"")) {
                connection.send("[{\"T\":\"subscription\",\"trades\":[\"AAPL\"],"
                        + "\"quotes\":[\"AAPL\"],\"bars\":[\"AAPL\"]}]");
                connection.send(BAR_FRAME);
                connection.send(BAR_FRAME);
            }
        }

        @Override
        public void onClose(WebSocket connection, int code, String reason, boolean remote) {}

        @Override
        public void onError(WebSocket connection, Exception exception) {}
    }
}
