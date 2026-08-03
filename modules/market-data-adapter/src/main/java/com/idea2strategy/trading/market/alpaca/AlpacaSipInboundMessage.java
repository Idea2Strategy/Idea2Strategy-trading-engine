package com.idea2strategy.trading.market.alpaca;

import java.util.List;

public sealed interface AlpacaSipInboundMessage {
    record Connected() implements AlpacaSipInboundMessage {}

    record Authenticated() implements AlpacaSipInboundMessage {}

    record SubscriptionConfirmed(List<String> barSymbols) implements AlpacaSipInboundMessage {
        public SubscriptionConfirmed {
            barSymbols = List.copyOf(barSymbols);
        }
    }

    record ProviderError(int code, String message) implements AlpacaSipInboundMessage {}

    record MinuteBar(AlpacaMarketInput input) implements AlpacaSipInboundMessage {}

    record UnsupportedFrame(String frameType) implements AlpacaSipInboundMessage {}
}
