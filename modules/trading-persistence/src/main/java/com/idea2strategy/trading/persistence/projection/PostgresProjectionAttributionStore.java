package com.idea2strategy.trading.persistence.projection;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class PostgresProjectionAttributionStore {
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public PostgresProjectionAttributionStore(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    public void attributeOrder(UUID orderId, ExecutionScope scope, Instant attributedAt) {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(scope, "scope");
        attributedAt = databaseInstant(attributedAt, "attributedAt");
        Instant normalizedAt = attributedAt;
        transaction.executeWithoutResult(status -> {
            jdbc.sql("""
                    insert into trading.execution_order_scope
                        (order_id, bot_id, partition_id, flow_id, attributed_at)
                    values (:orderId, :botId, :partitionId, :flowId, :attributedAt)
                    on conflict do nothing
                    """)
                    .param("orderId", orderId)
                    .param("botId", scope.botId())
                    .param("partitionId", scope.partitionId())
                    .param("flowId", scope.flowId())
                    .param("attributedAt", offset(normalizedAt))
                    .update();
            OrderAttribution stored = jdbc.sql("""
                    select bot_id, partition_id, flow_id, attributed_at
                    from trading.execution_order_scope where order_id = :orderId
                    """).param("orderId", orderId)
                    .query((rs, row) -> new OrderAttribution(
                            new ExecutionScope(
                                    rs.getObject("bot_id", UUID.class),
                                    rs.getObject("partition_id", UUID.class),
                                    rs.getObject("flow_id", UUID.class)),
                            rs.getObject("attributed_at", OffsetDateTime.class).toInstant()))
                    .optional().orElseThrow(() -> conflict("order scope was not stored"));
            if (!stored.scope().equals(scope) || !stored.attributedAt().equals(normalizedAt)) {
                throw conflict("order already has different scope attribution evidence");
            }
        });
    }

    public void attributeLedger(
            UUID transactionId, ExecutionScope scope, UUID orderId, Instant attributedAt) {
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(scope, "scope");
        attributedAt = databaseInstant(attributedAt, "attributedAt");
        Instant normalizedAt = attributedAt;
        try {
            transaction.executeWithoutResult(status -> {
                jdbc.sql("""
                        insert into trading.execution_ledger_scope
                            (transaction_id, bot_id, partition_id, flow_id, order_id, attributed_at)
                        values (:transactionId, :botId, :partitionId, :flowId, :orderId, :attributedAt)
                        on conflict do nothing
                        """)
                        .param("transactionId", transactionId)
                        .param("botId", scope.botId())
                        .param("partitionId", scope.partitionId())
                        .param("flowId", scope.flowId())
                        .param("orderId", orderId)
                        .param("attributedAt", offset(normalizedAt))
                        .update();
                LedgerAttribution stored = jdbc.sql("""
                        select bot_id, partition_id, flow_id, order_id, attributed_at
                        from trading.execution_ledger_scope where transaction_id = :transactionId
                        """).param("transactionId", transactionId)
                        .query((rs, row) -> new LedgerAttribution(
                                new ExecutionScope(
                                        rs.getObject("bot_id", UUID.class),
                                        rs.getObject("partition_id", UUID.class),
                                        rs.getObject("flow_id", UUID.class)),
                                rs.getObject("order_id", UUID.class),
                                rs.getObject("attributed_at", OffsetDateTime.class).toInstant()))
                        .optional().orElseThrow(() -> conflict("ledger scope was not stored"));
                if (!stored.scope().equals(scope)
                        || !Objects.equals(stored.orderId(), orderId)
                        || !stored.attributedAt().equals(normalizedAt)) {
                    throw conflict("ledger transaction already has different scope attribution evidence");
                }
            });
        } catch (DataIntegrityViolationException exception) {
            throw new ProjectionAttributionConflictException(
                    "ledger attribution violates source transaction scope", exception);
        }
    }

    public void appendReason(ProjectionReason reason) {
        Objects.requireNonNull(reason, "reason");
        transaction.executeWithoutResult(status -> {
            jdbc.sql("""
                    insert into trading.execution_projection_reason (
                        reason_id, bot_id, partition_id, flow_id, order_id,
                        reason_type, reason_code, detail, occurred_at)
                    values (:reasonId, :botId, :partitionId, :flowId, :orderId,
                        :reasonType, :reasonCode, :detail, :occurredAt)
                    on conflict do nothing
                    """)
                    .param("reasonId", reason.reasonId())
                    .param("botId", reason.scope().botId())
                    .param("partitionId", reason.scope().partitionId())
                    .param("flowId", reason.scope().flowId())
                    .param("orderId", reason.orderId())
                    .param("reasonType", reason.type().name())
                    .param("reasonCode", reason.code())
                    .param("detail", reason.detail())
                    .param("occurredAt", offset(reason.occurredAt()))
                    .update();
            ProjectionReason stored = jdbc.sql("""
                    select reason_id, bot_id, partition_id, flow_id, order_id,
                           reason_type, reason_code, detail, occurred_at
                    from trading.execution_projection_reason where reason_id = :reasonId
                    """).param("reasonId", reason.reasonId())
                    .query((rs, row) -> new ProjectionReason(
                            rs.getObject("reason_id", UUID.class),
                            new ExecutionScope(
                                    rs.getObject("bot_id", UUID.class),
                                    rs.getObject("partition_id", UUID.class),
                                    rs.getObject("flow_id", UUID.class)),
                            rs.getObject("order_id", UUID.class),
                            ProjectionReasonType.valueOf(rs.getString("reason_type")),
                            rs.getString("reason_code"),
                            rs.getString("detail"),
                            rs.getObject("occurred_at", OffsetDateTime.class).toInstant()))
                    .optional().orElseThrow(() -> conflict("reason was not stored"));
            if (!stored.equals(reason)) {
                throw conflict("reason identity was already used for different evidence");
            }
        });
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant databaseInstant(Instant instant, String name) {
        return Objects.requireNonNull(instant, name).truncatedTo(ChronoUnit.MICROS);
    }

    private static ProjectionAttributionConflictException conflict(String message) {
        return new ProjectionAttributionConflictException(message);
    }

    private record OrderAttribution(ExecutionScope scope, Instant attributedAt) {}

    private record LedgerAttribution(ExecutionScope scope, UUID orderId, Instant attributedAt) {}
}
