package com.idea2strategy.trading.market.alpaca;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public sealed interface AlpacaSipInboundMessage {
    record Connected() implements AlpacaSipInboundMessage {}

    record Authenticated() implements AlpacaSipInboundMessage {}

    record SubscriptionConfirmed(List<String> tradeSymbols) implements AlpacaSipInboundMessage {
        public SubscriptionConfirmed {
            tradeSymbols = List.copyOf(tradeSymbols);
        }
    }

    record ProviderError(int code, String message) implements AlpacaSipInboundMessage {}

    record TradeTick(
            String symbol,
            long tradeId,
            String exchange,
            BigDecimal price,
            BigDecimal size,
            Instant occurredAt,
            Instant receivedAt,
            List<String> conditions,
            String tape) implements AlpacaSipInboundMessage {
        public TradeTick {
            conditions = List.copyOf(conditions);
        }
    }

    record UnsupportedFrame(String frameType) implements AlpacaSipInboundMessage {}
}
