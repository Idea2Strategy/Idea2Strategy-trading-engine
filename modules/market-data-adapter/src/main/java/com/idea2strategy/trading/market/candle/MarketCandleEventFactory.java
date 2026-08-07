package com.idea2strategy.trading.market.candle;

import com.idea2strategy.trading.messaging.market.MarketCandle;
import com.idea2strategy.trading.messaging.market.MarketEventEnvelope;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Converts one finalized candle into the durable provider-neutral event contract. */
public final class MarketCandleEventFactory {
    public MarketEventEnvelope event(MarketCandle candle, Instant receivedAt) {
        Objects.requireNonNull(candle, "candle");
        String material = candle.instrumentId() + ":" + candle.timeframe().value() + ":" + candle.opensAt();
        String stableId = UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8)).toString();
        return new MarketEventEnvelope(
                "candle-" + stableId,
                1,
                candle.instrumentId(),
                "ALPACA",
                "SIP_30MIN_REST",
                candle.timeframe().eventType(),
                "bar-" + candle.timeframe().value() + "-" + candle.opensAt(),
                candle.closesAt(),
                Objects.requireNonNull(receivedAt, "receivedAt"),
                candle.closesAt().getEpochSecond() / 1800,
                0,
                null,
                candle.values());
    }
}
