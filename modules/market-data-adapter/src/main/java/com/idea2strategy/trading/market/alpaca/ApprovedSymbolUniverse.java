package com.idea2strategy.trading.market.alpaca;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;

public final class ApprovedSymbolUniverse {
    private final List<String> symbols;

    public ApprovedSymbolUniverse(Collection<String> symbols) {
        Objects.requireNonNull(symbols, "symbols");
        TreeSet<String> normalized = new TreeSet<>();
        for (String symbol : symbols) {
            String value = Objects.requireNonNull(symbol, "symbol").trim().toUpperCase(Locale.ROOT);
            if (value.isEmpty()) {
                throw new IllegalArgumentException("symbol must not be blank");
            }
            normalized.add(value);
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("approved universe must not be empty");
        }
        this.symbols = List.copyOf(normalized);
    }

    public List<String> symbols() {
        return symbols;
    }
}
