package com.idea2strategy.trading.worker.runtime;

import com.idea2strategy.trading.messaging.market.MarketEventEnvelope;
import com.idea2strategy.trading.messaging.market.MarketEventType;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
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
 * <p>The market gateway supplies completed one-minute bars. This state keeps those bars and rolls
 * them into every resolution the Basic editor exposes. A larger resolution only reports
 * {@code bar.closed.<resolution>=true} when a complete aggregate becomes available; the interpreter
 * therefore never trades from a partially formed candle.
 */
final class BasicMarketSignalState {

    private static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");
    private static final int MAX_BARS = 180;
    private static final List<Resolution> RESOLUTIONS = List.of(
            new Resolution("1m", 1), new Resolution("3m", 3),
            new Resolution("5m", 5), new Resolution("15m", 15),
            new Resolution("30m", 30), new Resolution("1h", 60),
            new Resolution("4h", 240), new Resolution("1d", 1_440),
            new Resolution("1w", 10_080));

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
            sessionOpen = first(event.values(), "open", "price", "close");
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
        boolean sessionClose = marketTime.getHour() == 16 && marketTime.getMinute() < 2;
        values.put("session.close", Boolean.toString(sessionClose));

        if (event.eventType() == MarketEventType.BAR_1M) {
            Bar bar = Bar.from(event);
            if (bar != null) {
                for (Series item : series.values()) {
                    if (item.accept(bar, event.occurredAt())) {
                        values.put("bar.closed." + item.resolution.code(), "true");
                    }
                }
            }
        }
        series.forEach((resolution, item) -> item.publish(values));
        return Map.copyOf(values);
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

    private record Resolution(String code, int minutes) {
        long bucket(Instant occurredAt) {
            ZonedDateTime marketTime = occurredAt.atZone(MARKET_ZONE);
            if ("1d".equals(code)) {
                return marketTime.toLocalDate().toEpochDay();
            }
            if ("1w".equals(code)) {
                LocalDate monday = marketTime.toLocalDate()
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                return monday.toEpochDay();
            }
            return Math.floorDiv(occurredAt.getEpochSecond(), minutes * 60L);
        }
    }

    private static final class Series {
        private final Resolution resolution;
        private final Deque<Bar> completed = new ArrayDeque<>();
        private Long bucket;
        private Bar forming;

        private Series(Resolution resolution) {
            this.resolution = resolution;
        }

        private boolean accept(Bar bar, Instant occurredAt) {
            if (resolution.minutes() == 1) {
                append(bar);
                return true;
            }
            long nextBucket = resolution.bucket(occurredAt);
            if (bucket == null) {
                bucket = nextBucket;
                forming = bar;
                return false;
            }
            if (bucket == nextBucket) {
                forming = forming.merge(bar);
                return false;
            }
            append(forming);
            bucket = nextBucket;
            forming = bar;
            return true;
        }

        private void append(Bar bar) {
            completed.addLast(bar);
            while (completed.size() > MAX_BARS) {
                completed.removeFirst();
            }
        }

        private void publish(Map<String, String> values) {
            if (completed.isEmpty()) {
                return;
            }
            String suffix = resolution.code();
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

        static Bar from(MarketEventEnvelope event) {
            BigDecimal close = first(event.values(), "close", "price");
            if (close == null) {
                return null;
            }
            BigDecimal open = first(event.values(), "open", "price", "close");
            BigDecimal high = first(event.values(), "high", "price", "close");
            BigDecimal low = first(event.values(), "low", "price", "close");
            BigDecimal volume = event.values().getOrDefault("volume", BigDecimal.ZERO);
            return new Bar(open, high, low, close, volume);
        }

        Bar merge(Bar next) {
            return new Bar(open, high.max(next.high), low.min(next.low), next.close,
                    volume.add(next.volume));
        }
    }
}
