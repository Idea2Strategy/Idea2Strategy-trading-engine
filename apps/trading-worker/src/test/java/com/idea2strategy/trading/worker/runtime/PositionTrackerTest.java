package com.idea2strategy.trading.worker.runtime;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idea2strategy.trading.worker.runtime.EvaluatingBotRuntime.PositionSnapshot;
import com.idea2strategy.trading.worker.runtime.EvaluatingBotRuntime.PositionTracker;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The position metrics a strategy reads, and the precision rules they are published under.
 *
 * <p>These values are what a {@code POSITION_RETURN} step compares against a threshold, and their
 * rendered form reaches the step trace. The official backtest publishes the same metrics from
 * {@code wiring._metric_percent}, so a difference in either the arithmetic or the rounding is a
 * difference in what the strategy decides.
 */
class PositionTrackerTest {

    private static final Instant OPENED_AT = Instant.parse("2025-12-01T14:30:00Z");
    private static final Instant OCCURRED_AT = Instant.parse("2025-12-01T21:00:00Z");

    /**
     * {@code precision:1.0.0} is 8 fractional digits with HALF_EVEN, and the backtest quantizes
     * these HALF_EVEN accordingly. Rounding HALF_UP here made one position publish two different
     * return percentages depending on which runtime computed it.
     *
     * <p>The entry price is chosen so the percentage is an exact tie at the ninth decimal whose
     * eighth digit is even, which is the only place the two modes disagree: a gain of
     * 2.5e-10 on an entry of 1 is 0.000000025 percent, and HALF_EVEN keeps the even 2 while
     * HALF_UP would carry it to 3.
     */
    @Test
    void publishesAPercentageRoundedHalfEven() {
        Map<String, String> values = publish(
                new BigDecimal("1"), new BigDecimal("1.00000000025"));

        assertEquals("0.00000002", values.get("position.returnPercent"));
    }

    @Test
    void publishesEveryMetricAStrategyCanRead() {
        Map<String, String> values = publish(new BigDecimal("100"), new BigDecimal("110"));

        assertAll(
                () -> assertEquals("100", values.get("position.averageEntryPrice")),
                () -> assertEquals(OPENED_AT.toString(), values.get("position.openedAt")),
                () -> assertEquals("10.00000000", values.get("position.returnPercent")),
                () -> assertEquals("10.00000000", values.get("position.peakReturnPercent")),
                () -> assertEquals("0.00000000", values.get("position.drawdownPercent")),
                () -> assertEquals("0", values.get("position.holdingTradingDays")));
    }

    /**
     * Adding to an open position changes its average entry price, but it does not begin a new
     * position cycle. Execution gates such as {@code 1회만} must therefore remain closed until the
     * position is fully exited and a later snapshot has a different {@code openedAt}.
     */
    @Test
    void identifiesAPositionCycleByItsOpeningInstantRatherThanItsAveragePrice() {
        PositionSnapshot original = new PositionSnapshot(new BigDecimal("100"), OPENED_AT);

        assertAll(
                () -> assertTrue(original.samePositionCycle(
                        new PositionSnapshot(new BigDecimal("104.25"), OPENED_AT))),
                () -> assertFalse(original.samePositionCycle(new PositionSnapshot(
                        new BigDecimal("104.25"), OPENED_AT.plusSeconds(1)))));
    }

    /**
     * Trading days are <em>elapsed</em> days, so the entry day is day zero.
     *
     * <p>This is why the catalog offers {@code 당일 장 마감} as its own option: if the entry day
     * counted as one, a one-trading-day hold would fire on the entry day too and the two options
     * would mean the same thing. The backtest counts the same way.
     */
    @Test
    void countsTradingDaysAsElapsedRatherThanInclusive() {
        PositionTracker tracker = new PositionTracker(
                new PositionSnapshot(new BigDecimal("100"), OPENED_AT), tradingSessions());
        Map<String, String> values = new LinkedHashMap<>();

        tracker.publish(
                values,
                new PositionSnapshot(new BigDecimal("100"), OPENED_AT),
                new BigDecimal("100"),
                Instant.parse("2025-12-02T21:00:00Z"),
                Map.of("bar.closed.30m", "true"));

        assertEquals("1", values.get("position.holdingTradingDays"));
    }

    /** The peak is the highest price seen, so a fall from it is a drawdown rather than a loss. */
    @Test
    void remembersThePeakAcrossBars() {
        PositionTracker tracker = new PositionTracker(
                new PositionSnapshot(new BigDecimal("100"), OPENED_AT), tradingSessions());
        publish(tracker, new BigDecimal("120"));

        Map<String, String> values = publish(tracker, new BigDecimal("110"));

        assertAll(
                () -> assertEquals("10.00000000", values.get("position.returnPercent")),
                () -> assertEquals("20.00000000", values.get("position.peakReturnPercent")),
                () -> assertEquals("8.33333333", values.get("position.drawdownPercent")));
    }

    @Test
    void scaleInUpdatesTheAverageWithoutResettingThePositionCyclePeak() {
        PositionTracker tracker = new PositionTracker(
                new PositionSnapshot(new BigDecimal("100"), OPENED_AT), tradingSessions());
        publish(tracker, new BigDecimal("120"));
        Map<String, String> values = new LinkedHashMap<>();

        tracker.publish(
                values,
                new PositionSnapshot(new BigDecimal("110"), OPENED_AT),
                new BigDecimal("110"),
                OCCURRED_AT.plusSeconds(60),
                Map.of("bar.closed.30m", "true"));

        assertAll(
                () -> assertEquals("110", values.get("position.averageEntryPrice")),
                () -> assertEquals("0.00000000", values.get("position.returnPercent")),
                () -> assertEquals("9.09090909", values.get("position.peakReturnPercent")),
                () -> assertEquals("8.33333333", values.get("position.drawdownPercent")));
    }

    /** The counter starts with the position, so its first closed bar is bar one. */
    @Test
    void countsTheEntryBarAsTheFirstHeldBar() {
        PositionTracker tracker = new PositionTracker(
                new PositionSnapshot(new BigDecimal("100"), OPENED_AT), tradingSessions());

        Map<String, String> values = publish(tracker, new BigDecimal("100"));

        assertEquals("1", values.get("position.holdingBars.30m"));
    }

    @Test
    void countsOnlyTheResolutionsWhoseBarClosed() {
        PositionTracker tracker = new PositionTracker(
                new PositionSnapshot(new BigDecimal("100"), OPENED_AT), tradingSessions());

        Map<String, String> values = publish(tracker, new BigDecimal("100"));

        assertAll(
                () -> assertEquals("1", values.get("position.holdingBars.30m")),
                () -> assertEquals("0", values.get("position.holdingBars.1h")),
                () -> assertEquals("0", values.get("position.holdingBars.4h")),
                () -> assertEquals("0", values.get("position.holdingBars.1d")));
    }

    private static Map<String, String> publish(BigDecimal entryPrice, BigDecimal price) {
        return publish(
                new PositionTracker(
                        new PositionSnapshot(entryPrice, OPENED_AT), tradingSessions()),
                entryPrice,
                price);
    }

    private static Map<String, String> publish(PositionTracker tracker, BigDecimal price) {
        return publish(tracker, new BigDecimal("100"), price);
    }

    private static Map<String, String> publish(
            PositionTracker tracker, BigDecimal averageEntryPrice, BigDecimal price) {
        Map<String, String> values = new LinkedHashMap<>();
        tracker.publish(
                values,
                new PositionSnapshot(averageEntryPrice, OPENED_AT),
                price,
                OCCURRED_AT,
                Map.of("bar.closed.30m", "true"));
        return values;
    }

    private static EvaluatingBotRuntime.TradingSessionCounter tradingSessions() {
        return (start, end) -> java.time.temporal.ChronoUnit.DAYS.between(
                start.atZone(java.time.ZoneId.of("America/New_York")).toLocalDate(),
                end.atZone(java.time.ZoneId.of("America/New_York")).toLocalDate());
    }
}
