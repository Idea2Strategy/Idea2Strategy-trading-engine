package com.idea2strategy.trading.gateway;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

record ApprovedInstruments(Map<String, UUID> bySymbol, int minimumCount) {
    ApprovedInstruments(Map<String, UUID> bySymbol) {
        this(bySymbol, 1);
    }

    ApprovedInstruments {
        bySymbol = Map.copyOf(Objects.requireNonNull(bySymbol, "bySymbol"));
        if (minimumCount < 1) {
            throw new IllegalArgumentException("minimum approved instrument count must be positive");
        }
        if (bySymbol.size() < minimumCount) {
            throw new IllegalArgumentException(
                    "approved instrument mapping has " + bySymbol.size()
                            + " symbols but requires at least " + minimumCount);
        }
    }
}
