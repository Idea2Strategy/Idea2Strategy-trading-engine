package com.idea2strategy.trading.market.alpaca;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class AlpacaSipSubscriptionManagerReconnectTest {

    @Test
    void resubscribesTheEntireApprovedUniverseExactlyOnceAfterReconnect() {
        Instant now = Instant.parse("2026-08-01T00:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        Set<String> approvedSymbols = new LinkedHashSet<>(
                IntStream.rangeClosed(1, 550)
                        .mapToObj(index -> "SYM%03d".formatted(index))
                        .toList());
        RecordingTransport transport = new RecordingTransport();
        ProviderRightsGate rightsGate = new ProviderRightsGate(
                () -> new ProviderRightsEvidence("alpaca", "sip", now.minusSeconds(60), now.plusSeconds(3600)),
                clock);
        AlpacaSipSubscriptionManager manager = new AlpacaSipSubscriptionManager(
                new ApprovedSymbolUniverse(approvedSymbols),
                rightsGate,
                () -> new AlpacaCredentials("environment-key", "environment-secret"),
                transport);
        manager.replaceTradeSubscriptions(approvedSymbols);

        manager.onConnected();
        manager.onAuthenticationApproved();
        manager.onAuthenticationApproved();
        manager.onDisconnected();
        manager.onConnected();
        manager.onAuthenticationApproved();
        manager.onAuthenticationApproved();

        assertEquals(2, transport.authenticationAttempts);
        assertEquals(2, transport.subscriptionBatches.size());
        for (List<String> batch : transport.subscriptionBatches) {
            assertEquals(550, batch.size());
            assertEquals(approvedSymbols, new LinkedHashSet<>(batch));
        }
    }

    private static final class RecordingTransport implements AlpacaSipTransport {
        private int authenticationAttempts;
        private final List<List<String>> subscriptionBatches = new ArrayList<>();

        @Override
        public void authenticate(AlpacaCredentials credentials) {
            authenticationAttempts++;
        }

        @Override
        public void subscribeTrades(List<String> symbols) {
            subscriptionBatches.add(List.copyOf(symbols));
        }

        @Override
        public void unsubscribeTrades(List<String> symbols) {}
    }
}
