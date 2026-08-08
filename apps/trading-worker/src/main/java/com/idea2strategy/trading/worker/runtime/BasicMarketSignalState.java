package com.idea2strategy.trading.worker.runtime;

import com.idea2strategy.trading.messaging.market.MarketEventEnvelope;
import com.idea2strategy.trading.messaging.market.MarketEventType;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.WeekFields;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the deterministic rolling bar inputs consumed by the complete Basic block catalog.
 *
 * <p>The market gateway supplies one evaluation event containing every strategy candle finalized
 * at a 30-minute boundary. This state keeps only 30m, 1h, 4h, and 1d candles, so display-only
 * minute bars can never trigger strategy evaluation.
 */
final class BasicMarketSignalState {

    private static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");
    private static final int MAX_BARS = 180;
    private static final List<Resolution> RESOLUTIONS = List.of(
            new Resolution("30m", "closed30m"),
            new Resolution("1h", "closed1h"),
            new Resolution("4h", "closed4h"),
            new Resolution("1d", "closed1d"));

    private final Map<String, Series> series = new LinkedHashMap<>();
    private LocalDate tradingDay;
    private long tradingDayIndex;
    private BigDecimal sessionOpen;

    BasicMarketSignalState() {
        RESOLUTIONS.forEach(resolution -> series.put(resolution.code(), new Series(resolution)));
    }

    Map<String, String> accept(MarketEventEnvelope event) {
        Map<String, String> values = new LinkedHashMap<>();
        RESOLUTIONS.forEach(resolution -> values.put("bar.closed." + resolution.code(), "false"));
        values.put("event.occurredAt", event.occurredAt().toString());

        ZonedDateTime marketTime = event.occurredAt().atZone(MARKET_ZONE);
        LocalDate eventDay = marketTime.toLocalDate();
        LocalDate previousTradingDay = tradingDay;
        boolean newTradingDay = !eventDay.equals(previousTradingDay);
        if (newTradingDay) {
            tradingDay = eventDay;
            tradingDayIndex++;
            sessionOpen = first(event.values(), "open30m", "open", "price", "close");
        }
        if (sessionOpen != null) {
            values.put("session.open", sessionOpen.toPlainString());
        }
        values.put("schedule.newTradingDay", Boolean.toString(newTradingDay));
        values.put("schedule.tradingDayIndex", Long.toString(tradingDayIndex));
        values.put("schedule.weekFirstTradingDay", Boolean.toString(newTradingDay
                && (previousTradingDay == null || weekKey(previousTradingDay) != weekKey(eventDay))));
        values.put("schedule.monthFirstTradingDay", Boolean.toString(newTradingDay
                && (previousTradingDay == null
                        || !YearMonth.from(previousTradingDay).equals(YearMonth.from(eventDay)))));
        values.put("schedule.monthLastTradingDay", Boolean.toString(newTradingDay
                && eventDay.equals(lastTradingDay(YearMonth.from(eventDay)))));
        /* The session's own close, taken from the calendar rather than from the clock. The daily
           candle is finalized at the session close, so closed1d is true on exactly the session's
           last boundary. A fixed 16:00 test was wrong on every early close - the day after
           Thanksgiving, Christmas Eve, July 3 all close at 13:00 ET - so on those days live never
           published session.close at all and a SESSION_CLOSE exit silently did not run, while the
           backtest, which reads the session's real closesAt, exited as written. */
        boolean sessionClose = event.eventType() == MarketEventType.MARKET_EVALUATION_READY
                && flag(event.values(), "closed1d");
        values.put("session.close", Boolean.toString(sessionClose));

        if (event.eventType() == MarketEventType.MARKET_EVALUATION_READY) {
            for (Series item : series.values()) {
                if (flag(event.values(), item.resolution.closedFlag())) {
                    Bar bar = Bar.from(event, item.resolution.code());
                    if (bar != null) {
                        item.append(bar);
                        values.put("bar.closed." + item.resolution.code(), "true");
                    }
                }
            }
        }
        series.forEach((resolution, item) -> item.publish(values));
        return Map.copyOf(values);
    }

    private static boolean flag(Map<String, BigDecimal> values, String key) {
        BigDecimal value = values.get(key);
        return value != null && value.signum() != 0;
    }

    private static BigDecimal first(Map<String, BigDecimal> values, String... keys) {
        for (String key : keys) {
            BigDecimal value = values.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static int weekKey(LocalDate date) {
        WeekFields fields = WeekFields.ISO;
        return date.get(fields.weekBasedYear()) * 100 + date.get(fields.weekOfWeekBasedYear());
    }

    private static LocalDate lastTradingDay(YearMonth month) {
        LocalDate date = month.atEndOfMonth();
        while (!isRegularTradingDay(date)) {
            date = date.minusDays(1);
        }
        return date;
    }

    /** Regular NYSE calendar holidays; extraordinary exchange closures remain provider-owned. */
    private static boolean isRegularTradingDay(LocalDate date) {
        if (date.getDayOfWeek() == DayOfWeek.SATURDAY
                || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return false;
        }
        int year = date.getYear();
        return !date.equals(observed(LocalDate.of(year, 1, 1)))
                && !date.equals(nthWeekday(year, 1, DayOfWeek.MONDAY, 3))
                && !date.equals(nthWeekday(year, 2, DayOfWeek.MONDAY, 3))
                && !date.equals(easterSunday(year).minusDays(2))
                && !date.equals(LocalDate.of(year, 5, 31)
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)))
                && !date.equals(observed(LocalDate.of(year, 6, 19)))
                && !date.equals(observed(LocalDate.of(year, 7, 4)))
                && !date.equals(nthWeekday(year, 9, DayOfWeek.MONDAY, 1))
                && !date.equals(nthWeekday(year, 11, DayOfWeek.THURSDAY, 4))
                && !date.equals(observed(LocalDate.of(year, 12, 25)));
    }

    private static LocalDate observed(LocalDate holiday) {
        return switch (holiday.getDayOfWeek()) {
            case SATURDAY -> holiday.minusDays(1);
            case SUNDAY -> holiday.plusDays(1);
            default -> holiday;
        };
    }

    private static LocalDate nthWeekday(
            int year, int month, DayOfWeek weekday, int occurrence) {
        return LocalDate.of(year, month, 1)
                .with(TemporalAdjusters.dayOfWeekInMonth(occurrence, weekday));
    }

    // Gregorian computus; NYSE Good Friday is two days before this date.
    private static LocalDate easterSunday(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = (h + l - 7 * m + 114) % 31 + 1;
        return LocalDate.of(year, month, day);
    }

    private record Resolution(String code, String closedFlag) {}

    private static final class Series {
        private final Resolution resolution;
        private final Deque<Bar> completed = new ArrayDeque<>();

        private Series(Resolution resolution) {
            this.resolution = resolution;
        }

        private void append(Bar bar) {
            completed.addLast(bar);
            while (completed.size() > MAX_BARS) {
                completed.removeFirst();
            }
        }

        private void publish(Map<String, String> values) {
            publish(values, resolution.code());
        }

        private void publish(Map<String, String> values, String suffix) {
            if (completed.isEmpty()) {
                return;
            }
            values.put("closes." + suffix, join(completed, Value.CLOSE));
            values.put("opens." + suffix, join(completed, Value.OPEN));
            values.put("highs." + suffix, join(completed, Value.HIGH));
            values.put("lows." + suffix, join(completed, Value.LOW));
            values.put("volumes." + suffix, join(completed, Value.VOLUME));
            values.put("price." + suffix, completed.getLast().close().toPlainString());
        }

        private static String join(Deque<Bar> bars, Value value) {
            return bars.stream().map(value::of).map(BigDecimal::toPlainString)
                    .collect(java.util.stream.Collectors.joining(","));
        }
    }

    private enum Value {
        OPEN { BigDecimal of(Bar bar) { return bar.open(); } },
        HIGH { BigDecimal of(Bar bar) { return bar.high(); } },
        LOW { BigDecimal of(Bar bar) { return bar.low(); } },
        CLOSE { BigDecimal of(Bar bar) { return bar.close(); } },
        VOLUME { BigDecimal of(Bar bar) { return bar.volume(); } };
        abstract BigDecimal of(Bar bar);
    }

    private record Bar(
            BigDecimal open, BigDecimal high, BigDecimal low,
            BigDecimal close, BigDecimal volume) {

        static Bar from(MarketEventEnvelope event, String suffix) {
            BigDecimal close = event.values().get("close" + suffix);
            if (close == null && "30m".equals(suffix)) {
                close = event.values().get("close");
            }
            if (close == null) {
                return null;
            }
            BigDecimal open = first(event.values(), "open" + suffix, "close" + suffix);
            BigDecimal high = first(event.values(), "high" + suffix, "close" + suffix);
            BigDecimal low = first(event.values(), "low" + suffix, "close" + suffix);
            BigDecimal volume = event.values().getOrDefault("volume" + suffix, BigDecimal.ZERO);
            return new Bar(open, high, low, close, volume);
        }
    }
}
