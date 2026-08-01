package com.idea2strategy.trading.market.alpaca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AlpacaSipRightsGateTest {
    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void missingRightsBlockCredentialAndNetworkAccess() {
        AtomicInteger credentialLoads = new AtomicInteger();
        RecordingTransport transport = new RecordingTransport();
        AlpacaSipSubscriptionManager manager = manager(
                new ProviderRightsGate(() -> null, Clock.fixed(NOW, ZoneOffset.UTC)),
                credentialLoads,
                transport);

        assertThrows(ProviderRightsUnavailableException.class, manager::onConnected);

        assertEquals(0, credentialLoads.get());
        assertEquals(0, transport.authenticationAttempts);
    }

    @Test
    void expiredRightsBlockCredentialAndNetworkAccess() {
        AtomicInteger credentialLoads = new AtomicInteger();
        RecordingTransport transport = new RecordingTransport();
        ProviderRightsEvidence expired = new ProviderRightsEvidence(
                "alpaca", "sip", NOW.minusSeconds(120), NOW.minusSeconds(1));
        AlpacaSipSubscriptionManager manager = manager(
                new ProviderRightsGate(() -> expired, Clock.fixed(NOW, ZoneOffset.UTC)),
                credentialLoads,
                transport);

        assertThrows(ProviderRightsUnavailableException.class, manager::onConnected);

        assertEquals(0, credentialLoads.get());
        assertEquals(0, transport.authenticationAttempts);
    }

    @Test
    void subscriptionIsActiveOnlyAfterTheEntireUniverseIsApproved() {
        AtomicInteger credentialLoads = new AtomicInteger();
        RecordingTransport transport = new RecordingTransport();
        ProviderRightsGate gate = new ProviderRightsGate(
                () -> new ProviderRightsEvidence("alpaca", "sip", NOW.minusSeconds(1), NOW.plusSeconds(60)),
                Clock.fixed(NOW, ZoneOffset.UTC));
        AlpacaSipSubscriptionManager manager = manager(gate, credentialLoads, transport);

        manager.onConnected();
        manager.onAuthenticationApproved();

        assertThrows(IllegalStateException.class, () -> manager.onSubscriptionApproved(List.of("AAPL")));
        manager.onSubscriptionApproved(List.of("AAPL", "MSFT"));
        assertEquals(true, manager.isSubscribed());
    }

    private static AlpacaSipSubscriptionManager manager(
            ProviderRightsGate gate,
            AtomicInteger credentialLoads,
            RecordingTransport transport) {
        return new AlpacaSipSubscriptionManager(
                new ApprovedSymbolUniverse(List.of("AAPL", "MSFT")),
                gate,
                () -> {
                    credentialLoads.incrementAndGet();
                    return new AlpacaCredentials("key", "secret");
                },
                transport);
    }

    private static final class RecordingTransport implements AlpacaSipTransport {
        private int authenticationAttempts;

        @Override
        public void authenticate(AlpacaCredentials credentials) {
            authenticationAttempts++;
        }

        @Override
        public void subscribe(List<String> symbols) {}
    }
}
