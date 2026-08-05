package com.idea2strategy.trading.market.alpaca;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

public enum AlpacaDataFeed {
    SIP("sip"),
    IEX("iex");

    private static final String OFFICIAL_HOST = "stream.data.alpaca.markets";
    private final String wireName;

    AlpacaDataFeed(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public String eventValue() {
        return name();
    }

    public URI officialEndpoint() {
        return URI.create("wss://" + OFFICIAL_HOST + "/v2/" + wireName);
    }

    public void validateEndpoint(URI endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        if (OFFICIAL_HOST.equalsIgnoreCase(endpoint.getHost())
                && !("/v2/" + wireName).equals(endpoint.getPath())) {
            throw new IllegalArgumentException(
                    "Alpaca endpoint does not match configured feed " + eventValue());
        }
    }

    public static AlpacaDataFeed parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Alpaca feed must be SIP or IEX");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Alpaca feed must be SIP or IEX", exception);
        }
    }
}
