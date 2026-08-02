package com.idea2strategy.trading.persistence.stop;

import com.idea2strategy.trading.application.port.BotStopSettlementStore;
import com.idea2strategy.trading.application.stop.StopStepResult;
import com.idea2strategy.trading.domain.stop.BotStopSettlement;
import com.idea2strategy.trading.domain.stop.StopStep;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class PostgresBotStopSettlementStore implements BotStopSettlementStore {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public PostgresBotStopSettlementStore(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    @Override
    public BotStopSettlement createOrLoad(BotStopSettlement desired) {
        Objects.requireNonNull(desired, "desired");
        return transactions.execute(status -> createOrLoadTx(desired));
    }

    @Override
    public BotStopSettlement load(UUID settlementId) {
        return load(settlementId, false).orElseThrow(() -> new IllegalArgumentException("unknown settlementId"));
    }

    @Override
    public List<BotStopSettlement> loadRecoverable() {
        return jdbc.sql("""
                select settlement_id, bot_id, stop_reason, reason_detail, checkpoint, version,
                       requested_at, updated_at, failed_step, terminal_reason
                from trading.bot_stop_settlement
                where checkpoint not in ('STOPPED', 'SETTLEMENT_FAILED')
                order by requested_at, settlement_id
                """).query((rs, row) -> view(rs).toDomain()).list();
    }

    @Override
    public BotStopSettlement recordStep(
            BotStopSettlement current, StopStep step, StopStepResult result, Instant occurredAt) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(occurredAt, "occurredAt");
        return transactions.execute(status -> recordStepTx(current, step, result, occurredAt));
    }

    private BotStopSettlement createOrLoadTx(BotStopSettlement desired) {
        int inserted = jdbc.sql("""
                insert into trading.bot_stop_settlement (
                    settlement_id, bot_id, stop_reason, reason_detail, checkpoint, version,
                    requested_at, updated_at, failed_step, terminal_reason
                ) values (:id, :botId, :reason, :detail, :checkpoint, :version,
                          :requestedAt, :updatedAt, null, null)
                on conflict do nothing
                """)
                .param("id", desired.settlementId()).param("botId", desired.botId())
                .param("reason", desired.reason().name()).param("detail", desired.reasonDetail())
                .param("checkpoint", desired.checkpoint().name()).param("version", desired.version())
                .param("requestedAt", offset(desired.requestedAt())).param("updatedAt", offset(desired.updatedAt()))
                .update();
        if (inserted == 1) {
            jdbc.sql("""
                    insert into trading.bot_stop_event (
                        settlement_id, version, from_checkpoint, to_checkpoint, occurred_at, reason
                    ) values (:id, :version, null, :checkpoint, :occurredAt, :reason)
                    """)
                    .param("id", desired.settlementId()).param("version", desired.version())
                    .param("checkpoint", desired.checkpoint().name()).param("occurredAt", offset(desired.requestedAt()))
                    .param("reason", desired.reason().name() + ":" + desired.reasonDetail()).update();
            return desired;
        }
        return loadByBot(desired.botId(), true).orElseThrow(() -> new IllegalStateException("stop request conflict"));
    }

    private BotStopSettlement recordStepTx(
            BotStopSettlement expected, StopStep step, StopStepResult result, Instant occurredAt) {
        BotStopSettlement stored = load(expected.settlementId(), true)
                .orElseThrow(() -> new IllegalArgumentException("unknown settlementId"));
        if (stored.version() != expected.version()) {
            return stored;
        }
        BotStopSettlement next = switch (result.status()) {
            case COMPLETED -> stored.completed(step, occurredAt);
            case PARTIAL, RETRYABLE -> stored.incomplete(step, occurredAt);
            case TERMINAL_FAILURE -> stored.failed(step, result.detail(), occurredAt);
        };
        int updated = jdbc.sql("""
                update trading.bot_stop_settlement
                set checkpoint = :checkpoint, version = :nextVersion, updated_at = :updatedAt,
                    failed_step = :failedStep, terminal_reason = :terminalReason
                where settlement_id = :id and version = :expectedVersion
                """)
                .param("checkpoint", next.checkpoint().name()).param("nextVersion", next.version())
                .param("updatedAt", offset(next.updatedAt()))
                .param("failedStep", next.failedStep() == null ? null : next.failedStep().name())
                .param("terminalReason", next.terminalReason()).param("id", next.settlementId())
                .param("expectedVersion", expected.version()).update();
        if (updated != 1) {
            return load(expected.settlementId());
        }
        jdbc.sql("""
                insert into trading.bot_stop_attempt (
                    settlement_id, resulting_version, operation_id, step, result_status, detail, occurred_at
                ) values (:id, :version, :operationId, :step, :status, :detail, :occurredAt)
                """)
                .param("id", next.settlementId()).param("version", next.version())
                .param("operationId", next.operationId(step)).param("step", step.name())
                .param("status", result.status().name()).param("detail", result.detail())
                .param("occurredAt", offset(occurredAt)).update();
        jdbc.sql("""
                insert into trading.bot_stop_event (
                    settlement_id, version, from_checkpoint, to_checkpoint, occurred_at, reason
                ) values (:id, :version, :fromCheckpoint, :toCheckpoint, :occurredAt, :reason)
                """)
                .param("id", next.settlementId()).param("version", next.version())
                .param("fromCheckpoint", stored.checkpoint().name()).param("toCheckpoint", next.checkpoint().name())
                .param("occurredAt", offset(occurredAt)).param("reason", result.detail()).update();
        return next;
    }

    private Optional<BotStopSettlement> loadByBot(UUID botId, boolean forUpdate) {
        return query("bot_id", botId, forUpdate);
    }

    private Optional<BotStopSettlement> load(UUID settlementId, boolean forUpdate) {
        return query("settlement_id", settlementId, forUpdate);
    }

    private Optional<BotStopSettlement> query(String column, UUID value, boolean forUpdate) {
        String lock = forUpdate ? " for update" : "";
        return jdbc.sql("""
                select settlement_id, bot_id, stop_reason, reason_detail, checkpoint, version,
                       requested_at, updated_at, failed_step, terminal_reason
                from trading.bot_stop_settlement where %s = :value
                """.formatted(column) + lock)
                .param("value", value)
                .query((rs, row) -> view(rs).toDomain()).optional();
    }

    private static BotStopSettlementView view(ResultSet rs) throws SQLException {
        return new BotStopSettlementView(
                rs.getObject("settlement_id", UUID.class), rs.getObject("bot_id", UUID.class),
                rs.getString("stop_reason"), rs.getString("reason_detail"), rs.getString("checkpoint"),
                rs.getLong("version"), instant(rs.getObject("requested_at", OffsetDateTime.class)),
                instant(rs.getObject("updated_at", OffsetDateTime.class)), rs.getString("failed_step"),
                rs.getString("terminal_reason"));
    }

    private static OffsetDateTime offset(Instant value) { return value.atOffset(ZoneOffset.UTC); }
    private static Instant instant(OffsetDateTime value) { return value.toInstant(); }
}
