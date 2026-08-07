package com.idea2strategy.trading.market.candle;

import com.idea2strategy.trading.market.session.OfficialMarketSession;
import com.idea2strategy.trading.messaging.market.MarketCandle;
import com.idea2strategy.trading.messaging.market.MarketTimeframe;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Builds finalized strategy candles on boundaries aligned to the official session open. */
public final class SessionAlignedCandleAggregator {

    public List<MarketCandle> closedAt(
            List<MarketCandle> completedThirtyMinuteCandles,
            OfficialMarketSession session,
            Instant boundary) {
        Objects.requireNonNull(completedThirtyMinuteCandles, "completedThirtyMinuteCandles");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(boundary, "boundary");
        if (boundary.isAfter(session.closesAt()) || !boundary.isAfter(session.opensAt())) {
            throw new IllegalArgumentException("boundary must be inside the official market session");
        }

        List<MarketCandle> source = completedThirtyMinuteCandles.stream()
                .sorted(Comparator.comparing(MarketCandle::opensAt))
                .toList();
        if (source.isEmpty() || !source.getLast().closesAt().equals(boundary)) {
            throw new IllegalArgumentException("the finalized 30m source must end at the boundary");
        }
        UUID instrumentId = source.getFirst().instrumentId();
        validateSource(source, session, boundary, instrumentId);

        List<MarketCandle> closed = new ArrayList<>();
        closed.add(source.getLast());
        addIfClosed(closed, source, session, boundary, instrumentId, MarketTimeframe.ONE_HOUR);
        addIfClosed(closed, source, session, boundary, instrumentId, MarketTimeframe.FOUR_HOURS);
        if (boundary.equals(session.closesAt())) {
            aggregateIfComplete(
                    source, instrumentId, MarketTimeframe.ONE_DAY,
                    session.opensAt(), session.closesAt(), false).ifPresent(closed::add);
        }
        return List.copyOf(closed);
    }

    private static void addIfClosed(
            List<MarketCandle> result,
            List<MarketCandle> source,
            OfficialMarketSession session,
            Instant boundary,
            UUID instrumentId,
            MarketTimeframe timeframe) {
        long periodSeconds = timeframe.duration().toSeconds();
        long elapsedSeconds = Duration.between(session.opensAt(), boundary).toSeconds();
        boolean officialClose = boundary.equals(session.closesAt());
        if (elapsedSeconds % periodSeconds != 0 && !officialClose) {
            return;
        }
        long groupIndex = Math.max(0, (elapsedSeconds - 1) / periodSeconds);
        Instant groupOpen = session.opensAt().plusSeconds(groupIndex * periodSeconds);
        List<MarketCandle> group = source.stream()
                .filter(candle -> !candle.opensAt().isBefore(groupOpen))
                .filter(candle -> !candle.closesAt().isAfter(boundary))
                .toList();
        boolean partial = Duration.between(groupOpen, boundary).compareTo(timeframe.duration()) < 0;
        aggregateIfComplete(group, instrumentId, timeframe, groupOpen, boundary, partial)
                .ifPresent(result::add);
    }

    private static java.util.Optional<MarketCandle> aggregateIfComplete(
            List<MarketCandle> source,
            UUID instrumentId,
            MarketTimeframe timeframe,
            Instant opensAt,
            Instant closesAt,
            boolean partial) {
        if (source.isEmpty()
                || !source.getFirst().opensAt().equals(opensAt)
                || !source.getLast().closesAt().equals(closesAt)) {
            return java.util.Optional.empty();
        }
        Instant expectedOpen = opensAt;
        for (MarketCandle candle : source) {
            if (!candle.opensAt().equals(expectedOpen)) {
                return java.util.Optional.empty();
            }
            expectedOpen = candle.closesAt();
        }
        BigDecimal high = source.stream().map(MarketCandle::high).max(BigDecimal::compareTo).orElseThrow();
        BigDecimal low = source.stream().map(MarketCandle::low).min(BigDecimal::compareTo).orElseThrow();
        BigDecimal volume = source.stream()
                .map(MarketCandle::volume)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return java.util.Optional.of(new MarketCandle(
                instrumentId,
                timeframe,
                opensAt,
                closesAt,
                source.getFirst().open(),
                high,
                low,
                source.getLast().close(),
                volume,
                partial));
    }

    private static void validateSource(
            List<MarketCandle> source,
            OfficialMarketSession session,
            Instant boundary,
            UUID instrumentId) {
        Instant previousClose = null;
        for (MarketCandle candle : source) {
            if (candle.timeframe() != MarketTimeframe.THIRTY_MINUTES) {
                throw new IllegalArgumentException("source candles must all be 30m");
            }
            if (!candle.instrumentId().equals(instrumentId)) {
                throw new IllegalArgumentException("source candles must belong to one instrument");
            }
            if (candle.opensAt().isBefore(session.opensAt())
                    || !Duration.between(candle.opensAt(), candle.closesAt()).equals(Duration.ofMinutes(30))) {
                throw new IllegalArgumentException("source candles must be official 30m session bars");
            }
            if (candle.closesAt().isAfter(boundary)) {
                throw new IllegalArgumentException("source candle closes after the requested boundary");
            }
            if (previousClose != null && candle.opensAt().isBefore(previousClose)) {
                throw new IllegalArgumentException("source candles must not overlap");
            }
            previousClose = candle.closesAt();
        }
    }
}
