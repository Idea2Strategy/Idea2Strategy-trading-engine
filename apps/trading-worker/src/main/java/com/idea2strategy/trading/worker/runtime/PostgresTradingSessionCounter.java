package com.idea2strategy.trading.worker.runtime;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Counts elapsed sessions from the versioned canonical market calendar. */
final class PostgresTradingSessionCounter
        implements EvaluatingBotRuntime.TradingSessionCounter {

    private static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");
    private static final String COUNT = """
            select count(*) filter (
                       where session_date > :startDate
                         and session_date <= :endDate
                         and cast(session_type as varchar) <> 'CLOSED'
                   ) as elapsed,
                   coalesce(bool_or(session_date = :startDate
                       and cast(session_type as varchar) <> 'CLOSED'), false) as start_session,
                   coalesce(bool_or(session_date = :endDate
                       and cast(session_type as varchar) <> 'CLOSED'), false) as end_session
              from market_data.trading_sessions
             where exchange_mic = :exchangeMic
               and calendar_version = :calendarVersion
               and session_date between :startDate and :endDate
            """;

    private final JdbcClient jdbc;
    private final String exchangeMic;
    private final String calendarVersion;

    PostgresTradingSessionCounter(
            JdbcClient jdbc, String exchangeMic, String calendarVersion) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.exchangeMic = requireText(exchangeMic, "exchangeMic");
        this.calendarVersion = requireText(calendarVersion, "calendarVersion");
    }

    @Override
    public long elapsed(Instant start, Instant end) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        LocalDate startDate = start.atZone(MARKET_ZONE).toLocalDate();
        LocalDate endDate = end.atZone(MARKET_ZONE).toLocalDate();
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("end must not precede start");
        }
        SessionCount count = jdbc.sql(COUNT)
                .param("exchangeMic", exchangeMic)
                .param("calendarVersion", calendarVersion)
                .param("startDate", startDate)
                .param("endDate", endDate)
                .query((resultSet, rowNumber) -> new SessionCount(
                        resultSet.getLong("elapsed"),
                        resultSet.getBoolean("start_session"),
                        resultSet.getBoolean("end_session")))
                .single();
        if (!count.startSession() || !count.endSession()) {
            throw new IllegalStateException(
                    "pinned market calendar does not cover both active session boundaries: "
                            + startDate + ".." + endDate + " (" + calendarVersion + ")");
        }
        return count.elapsed();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private record SessionCount(long elapsed, boolean startSession, boolean endSession) {}
}
