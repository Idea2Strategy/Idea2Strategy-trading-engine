package com.idea2strategy.trading.persistence.reservation;

import com.idea2strategy.trading.application.port.ResourceReservationStore;
import com.idea2strategy.trading.application.reservation.ConsumeReservationCommand;
import com.idea2strategy.trading.application.reservation.ReleaseReservationCommand;
import com.idea2strategy.trading.application.reservation.ResizeReservationCommand;
import com.idea2strategy.trading.application.reservation.ReservationCommand;
import com.idea2strategy.trading.application.reservation.ReservationConflictException;
import com.idea2strategy.trading.application.reservation.ReservationVersionConflictException;
import com.idea2strategy.trading.domain.reservation.LotReservationAllocation;
import com.idea2strategy.trading.domain.reservation.ReservationResourceType;
import com.idea2strategy.trading.domain.reservation.ReservationStatus;
import com.idea2strategy.trading.domain.reservation.ResourceReservation;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class PostgresResourceReservationStore implements ResourceReservationStore {
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public PostgresResourceReservationStore(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    @Override
    public ResourceReservation createOrLoad(ResourceReservation desired) {
        Objects.requireNonNull(desired, "desired");
        if (desired.version() != 1 || desired.status() != ReservationStatus.ACTIVE) {
            throw new IllegalArgumentException("only an initial reservation can be created");
        }
        return transaction.execute(status -> createInTransaction(desired));
    }

    @Override
    public ResourceReservation apply(ReservationCommand command) {
        Objects.requireNonNull(command, "command");
        return transaction.execute(status -> applyInTransaction(command));
    }

    private ResourceReservation createInTransaction(ResourceReservation desired) {
        int inserted = jdbc.sql("""
                insert into trading.execution_resource_reservation (
                    reservation_id, create_command_id, request_fingerprint, order_id, resource_type, resource_key,
                    reserved, consumed, released, status, version, created_at, updated_at, terminal_reason
                ) values (:id, :commandId, :fingerprint, :orderId, :type, :key,
                    :reserved, 0, 0, 'ACTIVE', 1, :createdAt, :createdAt, null)
                on conflict do nothing
                """).param("id", desired.reservationId()).param("commandId", desired.createCommandId())
                .param("fingerprint", desired.requestFingerprint()).param("orderId", desired.orderId())
                .param("type", desired.resourceType().name()).param("key", desired.resourceKey())
                .param("reserved", desired.reserved()).param("createdAt", offset(desired.createdAt())).update();
        if (inserted == 1) {
            replaceLots(desired);
            insertReceipt(desired.createCommandId(), desired.requestFingerprint(), desired);
            insertMovement(desired.reservationId(), 1, desired.createCommandId(), "RESERVE", desired.reserved(),
                    desired.createdAt(), null);
            return desired;
        }
        ResourceReservation stored = load(desired.reservationId(), false).orElseThrow(this::conflict);
        if (!stored.createCommandId().equals(desired.createCommandId())
                || !stored.requestFingerprint().equals(desired.requestFingerprint())) {
            throw conflict();
        }
        return stored;
    }

    private ResourceReservation applyInTransaction(ReservationCommand command) {
        String fingerprint = commandFingerprint(command);
        Optional<Receipt> receipt = receipt(command.commandId());
        if (receipt.isPresent()) return replay(receipt.orElseThrow(), command, fingerprint);

        ResourceReservation current = load(command.reservationId(), true).orElseThrow(this::conflict);
        receipt = receipt(command.commandId());
        if (receipt.isPresent()) return replay(receipt.orElseThrow(), command, fingerprint);
        if (current.version() != command.expectedVersion()) {
            throw new ReservationVersionConflictException(
                    "Expected reservation version %d but found %d".formatted(command.expectedVersion(), current.version()));
        }

        ResourceReservation next;
        String type;
        BigDecimal amount;
        String reason;
        if (command instanceof ConsumeReservationCommand consume) {
            next = current.consume(consume.amount(), consume.occurredAt());
            type = "CONSUME"; amount = consume.amount(); reason = null;
        } else if (command instanceof ResizeReservationCommand resize) {
            next = current.resize(resize.targetReserved(), resize.targetLotAllocations(), resize.occurredAt());
            int direction = resize.targetReserved().compareTo(current.reserved());
            type = direction > 0 ? "INCREASE" : "DECREASE";
            amount = resize.targetReserved().subtract(current.reserved()).abs();
            reason = null;
        } else if (command instanceof ReleaseReservationCommand release) {
            amount = current.remaining();
            next = current.releaseRemaining(release.occurredAt(), release.reason());
            type = "RELEASE"; reason = release.reason();
        } else {
            throw new IllegalArgumentException("unsupported reservation command");
        }

        insertReceipt(command.commandId(), fingerprint, next);
        insertMovement(next.reservationId(), next.version(), command.commandId(), type, amount,
                command.occurredAt(), reason);
        int updated = jdbc.sql("""
                update trading.execution_resource_reservation
                set consumed=:consumed, released=:released, status=:status, version=:nextVersion,
                    updated_at=:updatedAt, terminal_reason=:reason
                where reservation_id=:id and version=:expectedVersion
                """).param("consumed", next.consumed()).param("released", next.released())
                .param("status", next.status().name()).param("nextVersion", next.version())
                .param("updatedAt", offset(next.updatedAt())).param("reason", next.terminalReason())
                .param("id", next.reservationId()).param("expectedVersion", command.expectedVersion()).update();
        if (updated != 1) throw new ReservationVersionConflictException("reservation changed concurrently");
        replaceLots(next);
        return next;
    }

    private ResourceReservation replay(Receipt receipt, ReservationCommand command, String fingerprint) {
        if (!receipt.reservationId.equals(command.reservationId()) || !receipt.fingerprint.equals(fingerprint)) {
            throw conflict();
        }
        return load(command.reservationId(), false).orElseThrow(this::conflict);
    }

    private void insertReceipt(UUID commandId, String fingerprint, ResourceReservation result) {
        int count = jdbc.sql("""
                insert into trading.execution_resource_reservation_command
                    (command_id, request_fingerprint, reservation_id, resulting_version, result_status)
                values (:commandId, :fingerprint, :id, :version, :status)
                on conflict do nothing
                """).param("commandId", commandId).param("fingerprint", fingerprint)
                .param("id", result.reservationId()).param("version", result.version())
                .param("status", result.status().name()).update();
        if (count != 1) throw conflict();
    }

    private void insertMovement(UUID id, long version, UUID commandId, String type, BigDecimal amount,
                                java.time.Instant occurredAt, String reason) {
        int count = jdbc.sql("""
                insert into trading.execution_resource_reservation_movement
                    (reservation_id, version, command_id, movement_type, amount, occurred_at, reason)
                values (:id, :version, :commandId, :type, :amount, :occurredAt, :reason)
                """).param("id", id).param("version", version).param("commandId", commandId)
                .param("type", type).param("amount", amount).param("occurredAt", offset(occurredAt))
                .param("reason", reason).update();
        if (count != 1) throw conflict();
    }

    private void replaceLots(ResourceReservation value) {
        jdbc.sql("delete from trading.execution_resource_reservation_lot where reservation_id=:id")
                .param("id", value.reservationId()).update();
        for (LotReservationAllocation lot : value.lotAllocations()) {
            jdbc.sql("""
                    insert into trading.execution_resource_reservation_lot
                        (reservation_id, lot_id, opened_at, reserved, consumed, released)
                    values (:id, :lotId, :openedAt, :reserved, :consumed, :released)
                    """).param("id", value.reservationId()).param("lotId", lot.lotId())
                    .param("openedAt", offset(lot.openedAt())).param("reserved", lot.reserved())
                    .param("consumed", lot.consumed()).param("released", lot.released()).update();
        }
    }

    private Optional<ResourceReservation> load(UUID id, boolean lock) {
        return jdbc.sql("select * from trading.execution_resource_reservation where reservation_id=:id"
                        + (lock ? " for update" : ""))
                .param("id", id).query((rs, row) -> map(rs)).optional();
    }

    private ResourceReservation map(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("reservation_id", UUID.class);
        List<LotReservationAllocation> lots = jdbc.sql("""
                select lot_id, opened_at, reserved, consumed, released
                from trading.execution_resource_reservation_lot
                where reservation_id=:id order by opened_at, lot_id
                """).param("id", id).query((lot, row) -> new LotReservationAllocation(
                        lot.getObject("lot_id", UUID.class), lot.getObject("opened_at", OffsetDateTime.class).toInstant(),
                        lot.getBigDecimal("reserved"), lot.getBigDecimal("consumed"), lot.getBigDecimal("released"))).list();
        return new ResourceReservation(id, rs.getObject("create_command_id", UUID.class),
                rs.getString("request_fingerprint"), rs.getObject("order_id", UUID.class),
                ReservationResourceType.valueOf(rs.getString("resource_type")), rs.getString("resource_key"),
                rs.getBigDecimal("reserved"), rs.getBigDecimal("consumed"), rs.getBigDecimal("released"),
                ReservationStatus.valueOf(rs.getString("status")), rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(), rs.getString("terminal_reason"), lots);
    }

    private Optional<Receipt> receipt(UUID commandId) {
        return jdbc.sql("""
                select request_fingerprint, reservation_id
                from trading.execution_resource_reservation_command where command_id=:commandId
                """).param("commandId", commandId).query((rs, row) -> new Receipt(
                        rs.getString("request_fingerprint"), rs.getObject("reservation_id", UUID.class))).optional();
    }

    private static String commandFingerprint(ReservationCommand command) {
        String payload;
        if (command instanceof ConsumeReservationCommand consume) {
            payload = "CONSUME|%s|%d|%s|%s".formatted(consume.reservationId(), consume.expectedVersion(),
                    consume.amount().stripTrailingZeros().toPlainString(), consume.occurredAt());
        } else if (command instanceof ResizeReservationCommand resize) {
            String lots = resize.targetLotAllocations().stream()
                    .map(lot -> "%s,%s,%s,%s,%s".formatted(lot.lotId(), lot.openedAt(),
                            lot.reserved().stripTrailingZeros().toPlainString(),
                            lot.consumed().stripTrailingZeros().toPlainString(),
                            lot.released().stripTrailingZeros().toPlainString()))
                    .sorted().reduce("", (left, right) -> left + ";" + right);
            payload = "RESIZE|%s|%d|%s|%s|%s".formatted(resize.reservationId(), resize.expectedVersion(),
                    resize.targetReserved().stripTrailingZeros().toPlainString(), lots, resize.occurredAt());
        } else {
            ReleaseReservationCommand release = (ReleaseReservationCommand) command;
            payload = "RELEASE|%s|%d|%s|%s".formatted(release.reservationId(), release.expectedVersion(),
                    release.occurredAt(), release.reason());
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private ReservationConflictException conflict() { return new ReservationConflictException("reservation identity conflict"); }
    private static OffsetDateTime offset(java.time.Instant value) { return value.atOffset(ZoneOffset.UTC); }
    private record Receipt(String fingerprint, UUID reservationId) {}
}
