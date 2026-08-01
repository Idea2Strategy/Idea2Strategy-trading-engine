package com.idea2strategy.trading.market.alpaca;

@FunctionalInterface
public interface MarketDataRecoveryBoundary {
    void recover(String symbol, MissingSequenceRange missingRange);
}
