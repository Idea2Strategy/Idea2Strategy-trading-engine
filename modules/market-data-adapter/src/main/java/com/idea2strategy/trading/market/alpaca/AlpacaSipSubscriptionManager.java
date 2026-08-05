package com.idea2strategy.trading.market.alpaca;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;

public final class AlpacaSipSubscriptionManager {
    private final ApprovedSymbolUniverse universe;
    private final ProviderRightsGate rightsGate;
    private final AlpacaCredentialsProvider credentialsProvider;
    private final AlpacaSipTransport transport;
    private final AlpacaDataFeed feed;

    private boolean connected;
    private boolean authenticated;
    private boolean subscriptionRequested;
    private boolean subscriptionApproved;

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
        subscriptionRequested = false;
        subscriptionApproved = false;
    }

    public synchronized void onAuthenticationApproved() {
        if (!connected) {
            throw new IllegalStateException("SIP connection is not active");
        }
        rightsGate.requireCurrentAlpacaRights(feed);
        authenticated = true;
        if (!subscriptionRequested) {
            transport.subscribe(universe.symbols());
            subscriptionRequested = true;
        }
    }

    public synchronized void onSubscriptionApproved(Collection<String> approvedSymbols) {
        if (!authenticated || !subscriptionRequested) {
            throw new IllegalStateException("SIP subscription was not requested");
        }
        rightsGate.requireCurrentAlpacaRights(feed);
        ApprovedSymbolUniverse approved = new ApprovedSymbolUniverse(approvedSymbols);
        if (!new HashSet<>(universe.symbols()).equals(new HashSet<>(approved.symbols()))) {
            throw new IllegalStateException(
                    "Alpaca " + feed.eventValue() + " did not approve the entire configured universe");
        }
        subscriptionApproved = true;
    }

    public synchronized void onDisconnected() {
        connected = false;
        authenticated = false;
        subscriptionRequested = false;
        subscriptionApproved = false;
    }

    public synchronized boolean isAuthenticated() {
        return authenticated;
    }

    public synchronized boolean isSubscribed() {
        return subscriptionApproved;
    }
}
