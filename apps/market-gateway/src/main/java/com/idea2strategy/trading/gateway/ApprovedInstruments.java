package com.idea2strategy.trading.gateway;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

record ApprovedInstruments(Map<String, UUID> bySymbol) {
    ApprovedInstruments {
        bySymbol = Map.copyOf(Objects.requireNonNull(bySymbol, "bySymbol"));
        if (bySymbol.isEmpty()) {
            throw new IllegalArgumentException("approved instruments must not be empty");
        }
    }
}
