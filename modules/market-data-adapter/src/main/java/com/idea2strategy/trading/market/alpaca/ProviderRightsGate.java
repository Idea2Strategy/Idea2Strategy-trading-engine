package com.idea2strategy.trading.market.alpaca;

import java.time.Clock;
import java.util.Objects;
import java.util.function.Supplier;

public final class ProviderRightsGate {
    private final Supplier<ProviderRightsEvidence> evidenceSupplier;
    private final Clock clock;

    public ProviderRightsGate(Supplier<ProviderRightsEvidence> evidenceSupplier, Clock clock) {
        this.evidenceSupplier = Objects.requireNonNull(evidenceSupplier, "evidenceSupplier");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ProviderRightsEvidence requireCurrentAlpacaSipRights() {
        ProviderRightsEvidence evidence = evidenceSupplier.get();
        if (evidence == null) {
            throw new ProviderRightsUnavailableException("Alpaca SIP rights verification is missing");
        }
        if (!"alpaca".equals(evidence.provider()) || !"sip".equals(evidence.feed())) {
            throw new ProviderRightsUnavailableException("Rights evidence does not authorize Alpaca SIP");
        }
        if (!evidence.isCurrentAt(clock.instant())) {
            throw new ProviderRightsUnavailableException("Alpaca SIP rights verification is not current");
        }
        return evidence;
    }
}
