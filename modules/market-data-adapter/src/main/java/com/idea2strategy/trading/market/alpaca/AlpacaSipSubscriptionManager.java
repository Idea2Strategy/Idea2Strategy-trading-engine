package com.idea2strategy.trading.market.alpaca;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class AlpacaSipSubscriptionManager {
    private final ApprovedSymbolUniverse universe;
    private final ProviderRightsGate rightsGate;
    private final AlpacaCredentialsProvider credentialsProvider;
    private final AlpacaSipTransport transport;
    private final AlpacaDataFeed feed;

    private boolean connected;
    private boolean authenticated;
    private Set<String> desiredTradeSymbols = Set.of();
    private Set<String> activeTradeSymbols = Set.of();

    public AlpacaSipSubscriptionManager(
            ApprovedSymbolUniverse universe,
            ProviderRightsGate rightsGate,
            AlpacaCredentialsProvider credentialsProvider,
            AlpacaSipTransport transport) {
        this(universe, rightsGate, credentialsProvider, transport, AlpacaDataFeed.SIP);
    }

    public AlpacaSipSubscriptionManager(
            ApprovedSymbolUniverse universe,
            ProviderRightsGate rightsGate,
            AlpacaCredentialsProvider credentialsProvider,
            AlpacaSipTransport transport,
            AlpacaDataFeed feed) {
        this.universe = Objects.requireNonNull(universe, "universe");
        this.rightsGate = Objects.requireNonNull(rightsGate, "rightsGate");
        this.credentialsProvider = Objects.requireNonNull(credentialsProvider, "credentialsProvider");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.feed = Objects.requireNonNull(feed, "feed");
    }

    public synchronized void onConnected() {
        rightsGate.requireCurrentAlpacaRights(feed);
        AlpacaCredentials credentials = credentialsProvider.load();
        transport.authenticate(credentials);
        connected = true;
        authenticated = false;
        activeTradeSymbols = Set.of();
    }

    public synchronized void onAuthenticationApproved() {
        if (!connected) {
            throw new IllegalStateException("SIP connection is not active");
        }
        if (authenticated) {
            return;
        }
        rightsGate.requireCurrentAlpacaRights(feed);
        authenticated = true;
        if (!desiredTradeSymbols.isEmpty()) {
            transport.subscribeTrades(List.copyOf(desiredTradeSymbols));
        }
    }

    public synchronized void onSubscriptionApproved(Collection<String> approvedSymbols) {
        if (!authenticated) {
            throw new IllegalStateException("SIP connection is not authenticated");
        }
        rightsGate.requireCurrentAlpacaRights(feed);
        Set<String> approved = normalize(approvedSymbols);
        if (!new HashSet<>(universe.symbols()).containsAll(approved)) {
            throw new IllegalStateException(
                    "Alpaca " + feed.eventValue() + " approved a trade symbol outside the configured universe");
        }
        activeTradeSymbols = approved;
    }

    /** Reconciles chart demand without opening another Alpaca WebSocket connection. */
    public synchronized void replaceTradeSubscriptions(Collection<String> symbols) {
        Set<String> desired = normalize(symbols);
        if (!new HashSet<>(universe.symbols()).containsAll(desired)) {
            throw new IllegalArgumentException("trade subscription contains a symbol outside the approved universe");
        }
        Set<String> additions = new LinkedHashSet<>(desired);
        additions.removeAll(desiredTradeSymbols);
        Set<String> removals = new LinkedHashSet<>(desiredTradeSymbols);
        removals.removeAll(desired);
        desiredTradeSymbols = Set.copyOf(desired);
        if (authenticated) {
            if (!additions.isEmpty()) {
                transport.subscribeTrades(List.copyOf(additions));
            }
            if (!removals.isEmpty()) {
                transport.unsubscribeTrades(List.copyOf(removals));
            }
        }
    }

    public synchronized void onDisconnected() {
        connected = false;
        authenticated = false;
        activeTradeSymbols = Set.of();
    }

    public synchronized boolean isAuthenticated() {
        return authenticated;
    }

    public synchronized boolean isSubscribed() {
        return authenticated && activeTradeSymbols.equals(desiredTradeSymbols);
    }

    public synchronized Set<String> desiredTradeSymbols() {
        return desiredTradeSymbols;
    }

    private static Set<String> normalize(Collection<String> symbols) {
        Objects.requireNonNull(symbols, "symbols");
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String symbol : symbols) {
            if (symbol == null || symbol.isBlank()) {
                throw new IllegalArgumentException("symbol must not be blank");
            }
            normalized.add(symbol.trim().toUpperCase(Locale.ROOT));
        }
        return Set.copyOf(normalized);
    }
}
