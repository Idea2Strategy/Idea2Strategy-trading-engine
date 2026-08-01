package com.idea2strategy.trading.market.alpaca;

import java.util.Objects;

public record AlpacaCredentials(String apiKey, String apiSecret) {
    public AlpacaCredentials {
        apiKey = requireText(apiKey, "apiKey");
        apiSecret = requireText(apiSecret, "apiSecret");
    }

    private static String requireText(String value, String name) {
        String text = Objects.requireNonNull(value, name).trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return text;
    }
}
