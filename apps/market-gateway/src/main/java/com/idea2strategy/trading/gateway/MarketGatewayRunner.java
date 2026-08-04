package com.idea2strategy.trading.gateway;

import com.idea2strategy.trading.common.runtime.FileReadinessMarker;
import com.idea2strategy.trading.market.alpaca.AlpacaCredentialsProvider;
import com.idea2strategy.trading.market.alpaca.AlpacaMarketEventNormalizer;
import com.idea2strategy.trading.market.alpaca.AlpacaSipInboundMessage;
import com.idea2strategy.trading.market.alpaca.AlpacaSipMessageParser;
import com.idea2strategy.trading.market.alpaca.AlpacaSipSubscriptionManager;
import com.idea2strategy.trading.market.alpaca.AlpacaSipWebSocketTransport;
import com.idea2strategy.trading.market.alpaca.ApprovedSymbolUniverse;
import com.idea2strategy.trading.market.alpaca.MarketEventHandlingResult;
import com.idea2strategy.trading.market.alpaca.MarketEventHandlingStatus;
import com.idea2strategy.trading.market.alpaca.MarketEventOrderingProcessor;
import com.idea2strategy.trading.market.alpaca.ProviderRightsGate;
import com.idea2strategy.trading.market.alpaca.ProviderRightsUnavailableException;
import com.idea2strategy.trading.market.alpaca.ReconnectBackoff;
import com.idea2strategy.trading.market.alpaca.UnsupportedInstrumentException;
import com.idea2strategy.trading.market.availability.MarketDataAvailabilityResult;
import com.idea2strategy.trading.market.availability.MarketDataAvailabilityStatus;
import com.idea2strategy.trading.market.availability.MarketDataDegradationReason;
import com.idea2strategy.trading.market.redis.MarketEventPublishResult;
import com.idea2strategy.trading.market.redis.RedisMarketEventPublisher;
import com.idea2strategy.trading.messaging.market.MarketEventEnvelope;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

public final class MarketGatewayRunner implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(MarketGatewayRunner.class);

    private final URI endpoint;
    private final ApprovedSymbolUniverse universe;
    private final ProviderRightsGate rightsGate;
    private final AlpacaCredentialsProvider credentialsProvider;
    private final AlpacaSipMessageParser parser;
    private final AlpacaMarketEventNormalizer normalizer;
    private final MarketEventOrderingProcessor orderingProcessor;
    private final RedisMarketEventPublisher publisher;
    private final FileReadinessMarker readinessMarker;
    private final ReconnectBackoff backoff;
    private final Clock clock;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "market-gateway-reconnect");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger failedAttempts = new AtomicInteger();
    private final AtomicReference<WebSocket> activeSocket = new AtomicReference<>();
    private final Map<String, LongAdder> unpublishedFrames = new ConcurrentHashMap<>();
    private final Map<UUID, Long> latestSequenceByInstrument = new ConcurrentHashMap<>();
    private final Set<UUID> instrumentsWithSequenceGap = ConcurrentHashMap.newKeySet();
    private volatile boolean running;

    public MarketGatewayRunner(
            URI endpoint,
            ApprovedSymbolUniverse universe,
            ProviderRightsGate rightsGate,
            AlpacaCredentialsProvider credentialsProvider,
            AlpacaSipMessageParser parser,
            AlpacaMarketEventNormalizer normalizer,
            MarketEventOrderingProcessor orderingProcessor,
            RedisMarketEventPublisher publisher,
            FileReadinessMarker readinessMarker,
            ReconnectBackoff backoff,
            Clock clock) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.universe = Objects.requireNonNull(universe, "universe");
        this.rightsGate = Objects.requireNonNull(rightsGate, "rightsGate");
        this.credentialsProvider = Objects.requireNonNull(credentialsProvider, "credentialsProvider");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer");
        this.orderingProcessor = Objects.requireNonNull(orderingProcessor, "orderingProcessor");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.readinessMarker = Objects.requireNonNull(readinessMarker, "readinessMarker");
        this.backoff = Objects.requireNonNull(backoff, "backoff");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void start() {
        rightsGate.requireCurrentAlpacaSipRights();
        credentialsProvider.load();
        running = true;
        log.info("market-gateway connecting to {} for {} symbols", endpoint, universe.symbols().size());
        scheduler.execute(this::connect);
    }

    @Override
    public void stop() {
        running = false;
        readinessMarker.markNotReady();
        WebSocket socket = activeSocket.getAndSet(null);
        if (socket != null) {
            socket.abort();
        }
        scheduler.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void connect() {
        if (!running) {
            return;
        }
        httpClient.newWebSocketBuilder()
                .buildAsync(endpoint, new SipListener())
                .whenComplete((socket, failure) -> {
                    if (failure != null) {
                        log.warn("Alpaca SIP connection attempt failed: {}", failure.toString());
                        scheduleReconnect();
                        return;
                    }
                    activeSocket.set(socket);
                });
    }

    private void scheduleReconnect() {
        if (!running) {
            return;
        }
        int attempt = failedAttempts.incrementAndGet();
        Duration delay = backoff.delayForAttempt(attempt);
        log.info("Alpaca SIP reconnect attempt {} in {}", attempt, delay);
        scheduler.schedule(this::connect, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void stopForRightsFailure(ProviderRightsUnavailableException failure) {
        log.error("Alpaca SIP rights are no longer verified; the gateway stays down until restarted "
                + "with current rights evidence", failure);
        running = false;
        readinessMarker.markNotReady();
        WebSocket socket = activeSocket.getAndSet(null);
        if (socket != null) {
            socket.abort();
        }
    }

    private final class SipListener implements WebSocket.Listener {
        private final StringBuilder frame = new StringBuilder();
        private volatile AlpacaSipSubscriptionManager subscription;

        @Override
        public void onOpen(WebSocket webSocket) {
            subscription = new AlpacaSipSubscriptionManager(
                    universe,
                    rightsGate,
                    credentialsProvider,
                    AlpacaSipWebSocketTransport.connected(webSocket));
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            frame.append(data);
            if (last) {
                String text = frame.toString();
                frame.setLength(0);
                handle(webSocket, text);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            handleDisconnect("closed " + statusCode + " " + reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            handleDisconnect("errored: " + error);
        }

        private void handle(WebSocket webSocket, String text) {
            try {
                for (AlpacaSipInboundMessage message : parser.parse(text, clock.instant())) {
                    dispatch(message);
                }
            } catch (ProviderRightsUnavailableException exception) {
                stopForRightsFailure(exception);
            } catch (RuntimeException exception) {
                log.error("Alpaca SIP frame handling failed; dropping the connection to resynchronize", exception);
                webSocket.abort();
            }
        }

        private void dispatch(AlpacaSipInboundMessage message) {
            switch (message) {
                case AlpacaSipInboundMessage.Connected ignored -> subscription.onConnected();
                case AlpacaSipInboundMessage.Authenticated ignored -> subscription.onAuthenticationApproved();
                case AlpacaSipInboundMessage.SubscriptionConfirmed confirmed -> {
                    subscription.onSubscriptionApproved(confirmed.barSymbols());
                    failedAttempts.set(0);
                    readinessMarker.markReady();
                    log.info("Alpaca SIP subscription active for {} symbols", confirmed.barSymbols().size());
                }
                case AlpacaSipInboundMessage.ProviderError error -> {
                    log.warn("Alpaca SIP error {}: {}", error.code(), error.message());
                    readinessMarker.markNotReady();
                    publishUnavailable(MarketDataDegradationReason.PROVIDER_DISCONNECTED);
                }
                case AlpacaSipInboundMessage.MinuteBar bar -> publishBar(bar);
                case AlpacaSipInboundMessage.UnsupportedFrame unsupported ->
                        unpublishedFrames.computeIfAbsent(unsupported.frameType(), key -> new LongAdder())
                                .increment();
            }
        }

        private void publishBar(AlpacaSipInboundMessage.MinuteBar bar) {
            MarketEventEnvelope envelope;
            try {
                envelope = normalizer.normalize(bar.input());
            } catch (UnsupportedInstrumentException exception) {
                unpublishedFrames.computeIfAbsent("unsupported-instrument", key -> new LongAdder()).increment();
                return;
            }
            MarketEventHandlingResult handling = orderingProcessor.process(envelope);
            MarketEventPublishResult result = publisher.publish(handling);
            publishAvailability(envelope, handling);
            log.debug("bar {} {} handling={} publish={}",
                    envelope.instrumentId(), envelope.occurredAt(), handling.status(), result.status());
        }

        private void handleDisconnect(String reason) {
            readinessMarker.markNotReady();
            AlpacaSipSubscriptionManager manager = subscription;
            if (manager != null) {
                manager.onDisconnected();
            }
            activeSocket.set(null);
            publishUnavailable(MarketDataDegradationReason.PROVIDER_DISCONNECTED);
            if (!unpublishedFrames.isEmpty()) {
                log.info("Alpaca SIP frames received without a publishing path this connection: {}",
                        unpublishedFrames);
                unpublishedFrames.clear();
            }
            log.info("Alpaca SIP connection {}", reason);
            scheduleReconnect();
        }
    }

    private void publishAvailability(MarketEventEnvelope event, MarketEventHandlingResult handling) {
        if (handling.status() == MarketEventHandlingStatus.DUPLICATE
                || handling.status() == MarketEventHandlingStatus.STALE_CORRECTION) {
            return;
        }

        Long previous = latestSequenceByInstrument.putIfAbsent(event.instrumentId(), event.sequence());
        if (previous != null && event.sequence() > previous) {
            latestSequenceByInstrument.put(event.instrumentId(), event.sequence());
            if (event.sequence() > previous + 1) {
                instrumentsWithSequenceGap.add(event.instrumentId());
            }
        }

        Set<MarketDataDegradationReason> reasons = new HashSet<>();
        if (instrumentsWithSequenceGap.contains(event.instrumentId())) {
            reasons.add(MarketDataDegradationReason.SEQUENCE_GAP);
        }
        switch (handling.status()) {
            case OUT_OF_ORDER, SEQUENCE_CONFLICT, ORPHAN_CORRECTION ->
                    reasons.add(MarketDataDegradationReason.SEQUENCE_GAP);
            default -> { }
        }
        MarketDataAvailabilityResult availability = reasons.isEmpty()
                ? available()
                : degraded(reasons);
        publisher.publishAvailability(
                event.instrumentId(),
                Math.max(event.sequence(), latestSequenceByInstrument.get(event.instrumentId())),
                clock.instant(),
                availability);
    }

    private void publishUnavailable(MarketDataDegradationReason reason) {
        MarketDataAvailabilityResult availability = degraded(Set.of(reason));
        latestSequenceByInstrument.forEach((instrumentId, sequence) ->
                publisher.publishAvailability(instrumentId, sequence, clock.instant(), availability));
    }

    private static MarketDataAvailabilityResult available() {
        return new MarketDataAvailabilityResult(
                MarketDataAvailabilityStatus.AVAILABLE, true, true, Set.of(), java.util.List.of());
    }

    private static MarketDataAvailabilityResult degraded(Set<MarketDataDegradationReason> reasons) {
        return new MarketDataAvailabilityResult(
                MarketDataAvailabilityStatus.DEGRADED, false, false, reasons, java.util.List.of());
    }
}
