package com.idea2strategy.trading.persistence.stop;

import com.idea2strategy.trading.application.stop.StopStepResult;
import com.idea2strategy.trading.domain.stop.BotStopSettlement;
import com.idea2strategy.trading.domain.stop.StopCheckpoint;
import com.idea2strategy.trading.domain.stop.StopStep;
import java.util.UUID;

/**
 * The settlement snapshot this service writes into {@code bot.bot_events.summary_document}.
 *
 * <p>The document is a full snapshot rather than a delta. Canonical storage keeps no settlement
 * row, so reading the state back has to be one row read of the newest settlement event; a delta
 * would force a fold over the whole stream on every recovery poll and on every optimistic check.
 * The transition fields ({@code fromCheckpoint}, {@code step}, {@code resultStatus},
 * {@code resultDetail}, {@code operationId}) are what the private attempt and event tables held,
 * and they sit in the same row because those two tables always shared a key.
 *
 * <p>The document is assembled by hand. {@code trading-persistence} has no JSON library on its
 * compile classpath, and adding one to write six scalars would be a build change this concern does
 * not need. Reading is done by PostgreSQL's own {@code ->>} operator, so nothing parses JSON in
 * Java either.
 */
final class StopSettlementDocument {

    /** Every event type that carries a settlement snapshot. */
    static final String EVENT_TYPES =
            "'SETTLEMENT_REQUESTED', 'SETTLEMENT_STEP_RECORDED', 'SETTLEMENT_COMPLETED', "
                    + "'SETTLEMENT_FAILED'";

    /** The checkpoints {@code BotStopSettlement#terminal()} reports, as SQL literals. */
    static final String TERMINAL_CHECKPOINTS = "'STOPPED', 'SETTLEMENT_FAILED'";

    /** Projects one settlement event row back into the columns the view expects. */
    static final String PROJECTION = """
            summary_document ->> 'settlementId' as settlement_id,
            bot_id,
            summary_document ->> 'reason' as stop_reason,
            summary_document ->> 'reasonDetail' as reason_detail,
            summary_document ->> 'checkpoint' as checkpoint,
            cast(summary_document ->> 'version' as bigint) as version,
            summary_document ->> 'requestedAt' as requested_at,
            summary_document ->> 'updatedAt' as updated_at,
            summary_document ->> 'failedStep' as failed_step,
            summary_document ->> 'terminalReason' as terminal_reason
            """;

    private StopSettlementDocument() {}

    /** The snapshot of the requested settlement, which has no transition behind it yet. */
    static String requested(BotStopSettlement settlement) {
        return document(settlement, null, null, null, null);
    }

    /** The snapshot of a settlement that one step has just advanced. */
    static String transition(
            BotStopSettlement next,
            StopCheckpoint fromCheckpoint,
            StopStep step,
            StopStepResult result,
            UUID operationId) {
        return document(next, fromCheckpoint, step, result, operationId);
    }

    private static String document(
            BotStopSettlement settlement,
            StopCheckpoint fromCheckpoint,
            StopStep step,
            StopStepResult result,
            UUID operationId) {
        StringBuilder json = new StringBuilder(512).append('{');
        text(json, "settlementId", settlement.settlementId().toString()).append(',');
        text(json, "botId", settlement.botId().toString()).append(',');
        text(json, "reason", settlement.reason().name()).append(',');
        text(json, "reasonDetail", settlement.reasonDetail()).append(',');
        text(json, "checkpoint", settlement.checkpoint().name()).append(',');
        json.append("\"version\":").append(settlement.version()).append(',');
        text(json, "requestedAt", settlement.requestedAt().toString()).append(',');
        text(json, "updatedAt", settlement.updatedAt().toString()).append(',');
        text(json, "failedStep", settlement.failedStep() == null ? null : settlement.failedStep().name())
                .append(',');
        text(json, "terminalReason", settlement.terminalReason()).append(',');
        text(json, "fromCheckpoint", fromCheckpoint == null ? null : fromCheckpoint.name()).append(',');
        text(json, "step", step == null ? null : step.name()).append(',');
        text(json, "resultStatus", result == null ? null : result.status().name()).append(',');
        text(json, "resultDetail", result == null ? null : result.detail()).append(',');
        text(json, "operationId", operationId == null ? null : operationId.toString());
        return json.append('}').toString();
    }

    private static StringBuilder text(StringBuilder json, String name, String value) {
        json.append('"').append(name).append("\":");
        if (value == null) {
            return json.append("null");
        }
        json.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < 0x20) {
                        json.append("\\u").append(String.format("%04x", (int) character));
                    } else {
                        json.append(character);
                    }
                }
            }
        }
        return json.append('"');
    }
}
