package com.idea2strategy.trading.market.candle;

import com.idea2strategy.trading.messaging.market.MarketCandle;
import com.idea2strategy.trading.messaging.market.MarketEventEnvelope;
import com.idea2strategy.trading.messaging.market.MarketEventType;
import com.idea2strategy.trading.messaging.market.MarketTimeframe;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Collapses every timeframe finalized at one boundary into one durable evaluation trigger. */
public final class MarketEvaluationCoordinator {

    public MarketEventEnvelope ready(
            UUID instrumentId,
            Instant boundary,
            List<MarketCandle> candlesClosedAtBoundary,
            Instant receivedAt) {
        Objects.requireNonNull(instrumentId, "instrumentId");
        Objects.requireNonNull(boundary, "boundary");
        List<MarketCandle> candles = List.copyOf(
                Objects.requireNonNull(candlesClosedAtBoundary, "candlesClosedAtBoundary"));
        if (candles.isEmpty()) {
            throw new IllegalArgumentException("at least one finalized candle is required");
        }
        for (MarketCandle candle : candles) {
            if (!instrumentId.equals(candle.instrumentId()) || !boundary.equals(candle.closesAt())) {
                throw new IllegalArgumentException("all candles must belong to the instrument and boundary");
            }
        }
        MarketCandle thirtyMinute = candles.stream()
                .filter(candle -> candle.timeframe() == MarketTimeframe.THIRTY_MINUTES)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("the 30m finalized candle is required"));

        Map<String, BigDecimal> values = new LinkedHashMap<>();
        values.put("close", thirtyMinute.close());
        values.put("closed30m", flag(candles, MarketTimeframe.THIRTY_MINUTES));
        values.put("closed1h", flag(candles, MarketTimeframe.ONE_HOUR));
        values.put("closed4h", flag(candles, MarketTimeframe.FOUR_HOURS));
        values.put("closed1d", flag(candles, MarketTimeframe.ONE_DAY));
        candles.forEach(candle -> putCandle(values, candle));
        String material = instrumentId + ":" + boundary;
        String stableId = UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8)).toString();
        return new MarketEventEnvelope(
                "evaluation-ready-" + stableId,
                2,
                instrumentId,
                "ALPACA",
                "SIP_30MIN_REST",
                MarketEventType.MARKET_EVALUATION_READY,
                "evaluation-ready-" + boundary,
                boundary,
                Objects.requireNonNull(receivedAt, "receivedAt"),
                boundary.getEpochSecond() / 1800,
                0,
                null,
                values);
    }

    private static BigDecimal flag(List<MarketCandle> candles, MarketTimeframe timeframe) {
        return candles.stream().anyMatch(candle -> candle.timeframe() == timeframe)
                ? BigDecimal.ONE
                : BigDecimal.ZERO;
    }

    private static void putCandle(Map<String, BigDecimal> values, MarketCandle candle) {
        String suffix = switch (candle.timeframe()) {
            case THIRTY_MINUTES -> "30m";
            case ONE_HOUR -> "1h";
            case FOUR_HOURS -> "4h";
            case ONE_DAY -> "1d";
        };
        values.put("open" + suffix, candle.open());
        values.put("high" + suffix, candle.high());
        values.put("low" + suffix, candle.low());
        values.put("close" + suffix, candle.close());
        values.put("volume" + suffix, candle.volume());
    }
}
