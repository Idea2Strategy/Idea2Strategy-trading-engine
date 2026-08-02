package com.idea2strategy.trading.persistence.stop;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;

public final class JooqBotStopSettlementQuery {
    private final DSLContext dsl;

    public JooqBotStopSettlementQuery(DSLContext dsl) {
        this.dsl = java.util.Objects.requireNonNull(dsl, "dsl");
    }

    public Optional<BotStopSettlementView> find(UUID settlementId) {
        return dsl.fetchOptional("select * from trading.bot_stop_settlement where settlement_id = ?", settlementId)
                .map(this::view);
    }

    public List<AttemptView> attempts(UUID settlementId) {
        return dsl.fetch("""
                select resulting_version, operation_id, step, result_status, detail, occurred_at
                from trading.bot_stop_attempt where settlement_id = ? order by resulting_version
                """, settlementId).map(row -> new AttemptView(
                row.get("resulting_version", Long.class), row.get("operation_id", UUID.class),
                row.get("step", String.class), row.get("result_status", String.class),
                row.get("detail", String.class), row.get("occurred_at", OffsetDateTime.class).toInstant()));
    }

    private BotStopSettlementView view(Record row) {
        return new BotStopSettlementView(
                row.get("settlement_id", UUID.class), row.get("bot_id", UUID.class),
                row.get("stop_reason", String.class), row.get("reason_detail", String.class),
                row.get("checkpoint", String.class), row.get("version", Long.class),
                row.get("requested_at", OffsetDateTime.class).toInstant(),
                row.get("updated_at", OffsetDateTime.class).toInstant(),
                row.get("failed_step", String.class), row.get("terminal_reason", String.class));
    }

    public record AttemptView(long version, UUID operationId, String step, String status,
                              String detail, java.time.Instant occurredAt) {}
}
