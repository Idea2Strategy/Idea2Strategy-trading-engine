package com.idea2strategy.trading.market.alpaca;

@FunctionalInterface
public interface AlpacaCredentialsProvider {
    AlpacaCredentials load();
}
