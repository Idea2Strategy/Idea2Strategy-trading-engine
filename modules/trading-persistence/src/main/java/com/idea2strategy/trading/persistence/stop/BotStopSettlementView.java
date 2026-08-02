package com.idea2strategy.trading.persistence.stop;

import com.idea2strategy.trading.domain.stop.BotStopSettlement;
import com.idea2strategy.trading.domain.stop.StopCheckpoint;
import com.idea2strategy.trading.domain.stop.StopReason;
import com.idea2strategy.trading.domain.stop.StopStep;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

/**
 * One settlement snapshot read back out of {@code bot.bot_events.summary_document}.
 *
 * <p>The timestamps arrive as the ISO-8601 text the snapshot was written with rather than as the
 * event's own {@code timestamptz}. That is deliberate: {@code occurred_at} is truncated to the
 * microsecond the canonical column keeps, and the settlement record compares timestamps by value,
 * so a settlement has to be restored from what it was created with, not from that rounding of it.
 */
public record BotStopSettlementView(
        UUID settlementId, UUID botId, String stopReason, String reasonDetail, String checkpoint,
        long version, Instant requestedAt, Instant updatedAt, String failedStep, String terminalReason) {

    public BotStopSettlement toDomain() {
        return new BotStopSettlement(settlementId, botId, StopReason.valueOf(stopReason), reasonDetail,
                StopCheckpoint.valueOf(checkpoint), version, requestedAt, updatedAt,
                failedStep == null ? null : StopStep.valueOf(failedStep), terminalReason);
    }

    /** Maps a row produced by {@link StopSettlementDocument#PROJECTION}. */
    static BotStopSettlementView of(ResultSet rs) throws SQLException {
        return new BotStopSettlementView(
                UUID.fromString(rs.getString("settlement_id")),
                rs.getObject("bot_id", UUID.class),
                rs.getString("stop_reason"),
                rs.getString("reason_detail"),
                rs.getString("checkpoint"),
                rs.getLong("version"),
                Instant.parse(rs.getString("requested_at")),
                Instant.parse(rs.getString("updated_at")),
                rs.getString("failed_step"),
                rs.getString("terminal_reason"));
    }
}
