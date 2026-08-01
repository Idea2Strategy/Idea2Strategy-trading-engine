package com.idea2strategy.trading.persistence.order;

import com.idea2strategy.trading.application.order.CancelOrderCommand;
import com.idea2strategy.trading.application.order.ExpireOrderCommand;
import com.idea2strategy.trading.application.order.FillOrderCommand;
import com.idea2strategy.trading.application.order.OrderLifecycleCommand;
import com.idea2strategy.trading.application.order.OrderLifecycleConflictException;
import com.idea2strategy.trading.application.order.OrderLifecycleVersionConflictException;
import com.idea2strategy.trading.application.port.OrderLifecycleStore;
import com.idea2strategy.trading.domain.order.OrderLifecycle;
import com.idea2strategy.trading.domain.order.OrderStatus;
import com.idea2strategy.trading.domain.order.TimeInForce;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class PostgresOrderLifecycleStore implements OrderLifecycleStore {
    private static final String IDENTITY_CONFLICT = "Order lifecycle identity conflict";
    private static final int DATABASE_NUMERIC_PRECISION = 38;
    private static final int DATABASE_NUMERIC_SCALE = 18;

    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;

    public PostgresOrderLifecycleStore(JdbcClient jdbcClient, PlatformTransactionManager transactionManager) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    @Override
    public OrderLifecycle createOrLoad(OrderLifecycle desired) {
        Objects.requireNonNull(desired, "desired");
        requireInitialCreationAggregate(desired);
        requireExactlyPersistable(desired);
        return transactionTemplate.execute(status -> createOrLoadInTransaction(desired));
    }

    @Override
    public OrderLifecycle apply(OrderLifecycleCommand command) {
        Objects.requireNonNull(command, "command");
        return transactionTemplate.execute(status -> applyInTransaction(command));
    }

    private OrderLifecycle applyInTransaction(OrderLifecycleCommand command) {
        String requestFingerprint = commandFingerprint(command);
        Optional<CommandReceipt> existing = loadCommandReceipt(command.commandId());
        if (existing.isPresent()) {
            return replayOrConflict(existing.orElseThrow(), command, requestFingerprint);
        }

        OrderLifecycle current = loadByOrderId(command.orderId(), true).orElseThrow(PostgresOrderLifecycleStore::conflict);

        existing = loadCommandReceipt(command.commandId());
        if (existing.isPresent()) {
            return replayOrConflict(existing.orElseThrow(), command, requestFingerprint);
        }
        if (current.version() != command.expectedVersion()) {
            throw new OrderLifecycleVersionConflictException(
                    "Expected order version %d but found %d".formatted(command.expectedVersion(), current.version()));
        }

        OrderLifecycle next = applyToAggregate(current, command);
        requireExactlyPersistable(next);
        requireOne(insertCommandReceipt(command.commandId(), requestFingerprint, next));
        requireOne(insertTransition(current, next, command));
        int updated = updateSnapshot(command.expectedVersion(), next);
        if (updated != 1) {
            throw new OrderLifecycleVersionConflictException("Order version changed during lifecycle transition");
        }
        return next;
    }

    private OrderLifecycle replayOrConflict(
            CommandReceipt receipt, OrderLifecycleCommand command, String requestFingerprint) {
        if (!receipt.orderId().equals(command.orderId())
                || !receipt.requestFingerprint().equals(requestFingerprint)) {
            throw new OrderLifecycleConflictException("Order lifecycle command identity conflict");
        }
        return loadByOrderId(command.orderId(), false).orElseThrow(PostgresOrderLifecycleStore::conflict);
    }

    private static OrderLifecycle applyToAggregate(OrderLifecycle current, OrderLifecycleCommand command) {
        return switch (command) {
            case FillOrderCommand fill -> current.applyFill(fill.delta(), fill.occurredAt());
            case CancelOrderCommand cancel -> current.cancel(cancel.reason(), cancel.occurredAt());
            case ExpireOrderCommand expire -> current.expire(
                    expire.occurredAt(),
                    current.terms().timeInForce() == TimeInForce.DAY ? expire.daySessionClose() : null);
        };
    }

    private OrderLifecycle createOrLoadInTransaction(OrderLifecycle desired) {
        int inserted = jdbcClient.sql("""
                        insert into trading.trading_order (
                            order_id, create_command_id, request_fingerprint,
                            intent_id, candidate_id, instrument_id, side, quantity,
                            order_type, time_in_force, limit_price, stop_price, trail_percent,
                            expires_at, status, cumulative_filled_quantity, version,
                            created_at, last_transition_at, terminal_reason
                        ) values (
                            :orderId, :createCommandId, :requestFingerprint,
                            :intentId, :candidateId, :instrumentId, :side, :quantity,
                            :orderType, :timeInForce, :limitPrice, :stopPrice, :trailPercent,
                            :expiresAt, :status, :cumulativeFilledQuantity, :version,
                            :createdAt, :lastTransitionAt, :terminalReason
                        )
                        on conflict do nothing
                        """)
                .param("orderId", desired.orderId())
                .param("createCommandId", desired.createCommandId())
                .param("requestFingerprint", desired.requestFingerprint())
                .param("intentId", desired.terms().intentId())
                .param("candidateId", desired.terms().candidateId())
                .param("instrumentId", desired.terms().instrumentId())
                .param("side", desired.terms().side().name())
                .param("quantity", desired.terms().quantity())
                .param("orderType", desired.terms().type().name())
                .param("timeInForce", desired.terms().timeInForce().name())
                .param("limitPrice", desired.terms().limitPrice())
                .param("stopPrice", desired.terms().stopPrice())
                .param("trailPercent", desired.terms().trailPercent())
                .param("expiresAt", offset(desired.terms().expiresAt()))
                .param("status", desired.status().name())
                .param("cumulativeFilledQuantity", desired.cumulativeFilledQuantity())
                .param("version", desired.version())
                .param("createdAt", offset(desired.createdAt()))
                .param("lastTransitionAt", offset(desired.lastTransitionAt()))
                .param("terminalReason", desired.terminalReason())
                .update();
        if (inserted == 1) {
            requireOne(insertCreateReceipt(desired));
            requireOne(insertCreateTransition(desired));
            return desired;
        }

        OrderLifecycle stored = loadByIntentId(desired.terms().intentId()).orElseThrow(PostgresOrderLifecycleStore::conflict);
        if (!hasSameCreation(stored, desired)) {
            throw conflict();
        }
        return stored;
    }

    private static boolean hasSameCreation(OrderLifecycle stored, OrderLifecycle desired) {
        OrderStatus storedInitialStatus = stored.status() == OrderStatus.REJECTED
                ? OrderStatus.REJECTED
                : OrderStatus.ACCEPTED;
        String storedInitialReason = storedInitialStatus == OrderStatus.REJECTED ? stored.terminalReason() : null;
        return stored.orderId().equals(desired.orderId())
                && stored.createCommandId().equals(desired.createCommandId())
                && stored.requestFingerprint().equals(desired.requestFingerprint())
                && stored.terms().equals(desired.terms())
                && stored.createdAt().equals(desired.createdAt())
                && storedInitialStatus == desired.status()
                && Objects.equals(storedInitialReason, desired.terminalReason());
    }

    private int insertCreateReceipt(OrderLifecycle desired) {
        return jdbcClient.sql("""
                        insert into trading.order_lifecycle_command (
                            command_id, request_fingerprint, order_id, resulting_version, result_status
                        ) values (
                            :commandId, :requestFingerprint, :orderId, :resultingVersion, :resultStatus
                        )
                        on conflict do nothing
                        """)
                .param("commandId", desired.createCommandId())
                .param("requestFingerprint", desired.requestFingerprint())
                .param("orderId", desired.orderId())
                .param("resultingVersion", desired.version())
                .param("resultStatus", desired.status().name())
                .update();
    }

    private int insertCommandReceipt(UUID commandId, String requestFingerprint, OrderLifecycle result) {
        return jdbcClient.sql("""
                        insert into trading.order_lifecycle_command (
                            command_id, request_fingerprint, order_id, resulting_version, result_status
                        ) values (
                            :commandId, :requestFingerprint, :orderId, :resultingVersion, :resultStatus
                        )
                        on conflict do nothing
                        """)
                .param("commandId", commandId)
                .param("requestFingerprint", requestFingerprint)
                .param("orderId", result.orderId())
                .param("resultingVersion", result.version())
                .param("resultStatus", result.status().name())
                .update();
    }

    private int insertCreateTransition(OrderLifecycle desired) {
        return jdbcClient.sql("""
                        insert into trading.order_lifecycle_transition (
                            order_id, version, command_id, from_status, to_status,
                            fill_delta, cumulative_filled_quantity, occurred_at, reason
                        ) values (
                            :orderId, :version, :commandId, null, :toStatus,
                            null, :cumulativeFilledQuantity, :occurredAt, :reason
                        )
                        on conflict do nothing
                        """)
                .param("orderId", desired.orderId())
                .param("version", desired.version())
                .param("commandId", desired.createCommandId())
                .param("toStatus", desired.status().name())
                .param("cumulativeFilledQuantity", desired.cumulativeFilledQuantity())
                .param("occurredAt", offset(desired.lastTransitionAt()))
                .param("reason", desired.terminalReason())
                .update();
    }

    private int insertTransition(OrderLifecycle current, OrderLifecycle next, OrderLifecycleCommand command) {
        BigDecimal fillDelta = command instanceof FillOrderCommand fill ? fill.delta() : null;
        return jdbcClient.sql("""
                        insert into trading.order_lifecycle_transition (
                            order_id, version, command_id, from_status, to_status,
                            fill_delta, cumulative_filled_quantity, occurred_at, reason
                        ) values (
                            :orderId, :version, :commandId, :fromStatus, :toStatus,
                            :fillDelta, :cumulativeFilledQuantity, :occurredAt, :reason
                        )
                        on conflict do nothing
                        """)
                .param("orderId", next.orderId())
                .param("version", next.version())
                .param("commandId", command.commandId())
                .param("fromStatus", current.status().name())
                .param("toStatus", next.status().name())
                .param("fillDelta", fillDelta)
                .param("cumulativeFilledQuantity", next.cumulativeFilledQuantity())
                .param("occurredAt", offset(next.lastTransitionAt()))
                .param("reason", next.terminalReason())
                .update();
    }

    private int updateSnapshot(long expectedVersion, OrderLifecycle next) {
        return jdbcClient.sql("""
                        update trading.trading_order
                        set status = :status,
                            cumulative_filled_quantity = :cumulativeFilledQuantity,
                            version = :nextVersion,
                            last_transition_at = :lastTransitionAt,
                            terminal_reason = :terminalReason
                        where order_id = :orderId
                          and version = :expectedVersion
                        """)
                .param("status", next.status().name())
                .param("cumulativeFilledQuantity", next.cumulativeFilledQuantity())
                .param("nextVersion", next.version())
                .param("lastTransitionAt", offset(next.lastTransitionAt()))
                .param("terminalReason", next.terminalReason())
                .param("orderId", next.orderId())
                .param("expectedVersion", expectedVersion)
                .update();
    }

    private Optional<OrderLifecycle> loadByIntentId(UUID intentId) {
        return jdbcClient.sql("""
                        select order_id, create_command_id, request_fingerprint,
                               intent_id, candidate_id, instrument_id, side, quantity,
                               order_type, time_in_force, limit_price, stop_price, trail_percent,
                               expires_at, status, cumulative_filled_quantity, version,
                               created_at, last_transition_at, terminal_reason
                        from trading.trading_order
                        where intent_id = :intentId
                        """)
                .param("intentId", intentId)
                .query((resultSet, rowNumber) -> toView(resultSet))
                .optional()
                .map(OrderLifecyclePersistenceView::toDomain);
    }

    private Optional<OrderLifecycle> loadByOrderId(UUID orderId, boolean forUpdate) {
        String lock = forUpdate ? " for update" : "";
        return jdbcClient.sql("""
                        select order_id, create_command_id, request_fingerprint,
                               intent_id, candidate_id, instrument_id, side, quantity,
                               order_type, time_in_force, limit_price, stop_price, trail_percent,
                               expires_at, status, cumulative_filled_quantity, version,
                               created_at, last_transition_at, terminal_reason
                        from trading.trading_order
                        where order_id = :orderId
                        """ + lock)
                .param("orderId", orderId)
                .query((resultSet, rowNumber) -> toView(resultSet))
                .optional()
                .map(OrderLifecyclePersistenceView::toDomain);
    }

    private Optional<CommandReceipt> loadCommandReceipt(UUID commandId) {
        return jdbcClient.sql("""
                        select command_id, request_fingerprint, order_id, resulting_version, result_status
                        from trading.order_lifecycle_command
                        where command_id = :commandId
                        """)
                .param("commandId", commandId)
                .query((resultSet, rowNumber) -> new CommandReceipt(
                        resultSet.getObject("command_id", UUID.class),
                        resultSet.getString("request_fingerprint"),
                        resultSet.getObject("order_id", UUID.class),
                        resultSet.getLong("resulting_version"),
                        OrderStatus.valueOf(resultSet.getString("result_status"))))
                .optional();
    }

    private static OrderLifecyclePersistenceView toView(ResultSet resultSet) throws SQLException {
        return new OrderLifecyclePersistenceView(
                resultSet.getObject("order_id", UUID.class),
                resultSet.getObject("create_command_id", UUID.class),
                resultSet.getString("request_fingerprint"),
                resultSet.getObject("intent_id", UUID.class),
                resultSet.getObject("candidate_id", UUID.class),
                resultSet.getObject("instrument_id", UUID.class),
                resultSet.getString("side"),
                resultSet.getBigDecimal("quantity"),
                resultSet.getString("order_type"),
                resultSet.getString("time_in_force"),
                resultSet.getBigDecimal("limit_price"),
                resultSet.getBigDecimal("stop_price"),
                resultSet.getBigDecimal("trail_percent"),
                instant(resultSet.getObject("expires_at", OffsetDateTime.class)),
                resultSet.getString("status"),
                resultSet.getBigDecimal("cumulative_filled_quantity"),
                resultSet.getLong("version"),
                instant(resultSet.getObject("created_at", OffsetDateTime.class)),
                instant(resultSet.getObject("last_transition_at", OffsetDateTime.class)),
                resultSet.getString("terminal_reason"));
    }

    private static String commandFingerprint(OrderLifecycleCommand command) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        write(digest, "order-lifecycle-command:v1");
        write(digest, commandType(command));
        write(digest, command.commandId().toString());
        write(digest, command.orderId().toString());
        write(digest, Long.toString(command.expectedVersion()));
        write(digest, command.occurredAt().toString());
        switch (command) {
            case FillOrderCommand fill -> write(digest, fill.delta().stripTrailingZeros().toPlainString());
            case CancelOrderCommand cancel -> write(digest, cancel.reason());
            case ExpireOrderCommand expire -> write(
                    digest, expire.daySessionClose() == null ? null : expire.daySessionClose().toString());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String commandType(OrderLifecycleCommand command) {
        return switch (command) {
            case FillOrderCommand ignored -> "FILL";
            case CancelOrderCommand ignored -> "CANCEL";
            case ExpireOrderCommand ignored -> "EXPIRE";
        };
    }

    private static void requireExactlyPersistable(OrderLifecycle lifecycle) {
        requireDatabaseDecimal(lifecycle.terms().quantity(), "quantity");
        requireDatabaseDecimal(lifecycle.terms().limitPrice(), "limitPrice");
        requireDatabaseDecimal(lifecycle.terms().stopPrice(), "stopPrice");
        requireDatabaseDecimal(lifecycle.terms().trailPercent(), "trailPercent");
        requireDatabaseDecimal(lifecycle.cumulativeFilledQuantity(), "cumulativeFilledQuantity");
        requireDatabaseInstant(lifecycle.terms().expiresAt(), "expiresAt");
        requireDatabaseInstant(lifecycle.createdAt(), "createdAt");
        requireDatabaseInstant(lifecycle.lastTransitionAt(), "lastTransitionAt");
    }

    private static void requireInitialCreationAggregate(OrderLifecycle desired) {
        if (desired.version() != 1
                || (desired.status() != OrderStatus.ACCEPTED && desired.status() != OrderStatus.REJECTED)) {
            throw new IllegalArgumentException("createOrLoad requires an initial ACCEPTED or REJECTED aggregate");
        }
    }

    private static void requireDatabaseDecimal(BigDecimal value, String name) {
        if (value == null) {
            return;
        }
        BigDecimal databaseValue;
        try {
            databaseValue = value.setScale(DATABASE_NUMERIC_SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException notExactlyRepresentable) {
            throw new IllegalArgumentException(name + " exceeds PostgreSQL numeric(38,18) scale", notExactlyRepresentable);
        }
        if (databaseValue.precision() > DATABASE_NUMERIC_PRECISION) {
            throw new IllegalArgumentException(name + " exceeds PostgreSQL numeric(38,18) precision");
        }
    }

    private static void requireDatabaseInstant(Instant value, String name) {
        if (value != null && value.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException(name + " must have microsecond precision for PostgreSQL");
        }
    }

    private static void write(MessageDigest digest, String value) {
        if (value == null) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime offset(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static void requireOne(int inserted) {
        if (inserted != 1) {
            throw conflict();
        }
    }

    private static OrderLifecycleConflictException conflict() {
        return new OrderLifecycleConflictException(IDENTITY_CONFLICT);
    }

    private record CommandReceipt(
            UUID commandId,
            String requestFingerprint,
            UUID orderId,
            long resultingVersion,
            OrderStatus resultStatus) {
    }
}
