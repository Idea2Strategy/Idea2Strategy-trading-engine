package com.idea2strategy.trading.persistence.fill;

import com.idea2strategy.trading.application.fill.FillRecordConflictException;
import com.idea2strategy.trading.application.port.FillRecordStore;
import com.idea2strategy.trading.domain.fill.FillRecord;
import com.idea2strategy.trading.domain.fill.FillRecordKind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class PostgresFillRecordStore implements FillRecordStore {
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    public PostgresFillRecordStore(JdbcClient jdbc, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transaction = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
    }

    @Override public FillRecord appendOrLoad(FillRecord desired) {
        Objects.requireNonNull(desired, "desired");
        return transaction.execute(status -> appendInTransaction(desired));
    }

    private FillRecord appendInTransaction(FillRecord desired) {
        Optional<FillRecord> existing = findById(desired.fillRecordId());
        if (existing.isPresent()) return replay(existing.orElseThrow(), desired);

        Optional<FillRecord> latest = findLatest(desired.orderId(), desired.sourceExecutionId(), true);
        if (desired.revision() == 0) {
            if (latest.isPresent()) throw conflict("original execution already exists");
        } else {
            FillRecord previous = latest.orElseThrow(() -> conflict("correction arrived before original"));
            if (previous.revision() + 1 != desired.revision()
                    || !previous.fillRecordId().equals(desired.correctionOfRecordId())) {
                throw conflict("correction is not the next revision");
            }
        }

        int inserted = jdbc.sql("""
                insert into trading.execution_fill_record (
                    fill_record_id, request_fingerprint, root_fill_id, correction_of_record_id,
                    order_id, source_execution_id, revision, kind, quantity, price, commission, slippage,
                    occurred_at, received_at
                ) values (:id, :fingerprint, :rootId, :previousId, :orderId, :sourceId, :revision, :kind,
                    :quantity, :price, :commission, :slippage, :occurredAt, :receivedAt)
                on conflict do nothing
                """).param("id", desired.fillRecordId()).param("fingerprint", desired.requestFingerprint())
                .param("rootId", desired.rootFillId()).param("previousId", desired.correctionOfRecordId())
                .param("orderId", desired.orderId()).param("sourceId", desired.sourceExecutionId())
                .param("revision", desired.revision()).param("kind", desired.kind().name())
                .param("quantity", desired.quantity()).param("price", desired.price())
                .param("commission", desired.commission()).param("slippage", desired.slippage())
                .param("occurredAt", desired.occurredAt().atOffset(ZoneOffset.UTC))
                .param("receivedAt", desired.receivedAt().atOffset(ZoneOffset.UTC)).update();
        if (inserted != 1) {
            FillRecord raced = findById(desired.fillRecordId()).orElseThrow(() -> conflict("fill revision conflict"));
            return replay(raced, desired);
        }
        return desired;
    }

    private FillRecord replay(FillRecord stored, FillRecord desired) {
        if (!stored.requestFingerprint().equals(desired.requestFingerprint())) {
            throw conflict("fill record identity conflict");
        }
        return stored;
    }

    private Optional<FillRecord> findById(UUID id) {
        return jdbc.sql("select * from trading.execution_fill_record where fill_record_id=:id")
                .param("id", id).query((rs, row) -> map(rs)).optional();
    }

    private Optional<FillRecord> findLatest(UUID orderId, String sourceId, boolean lock) {
        return jdbc.sql("""
                select * from trading.execution_fill_record
                where order_id=:orderId and source_execution_id=:sourceId
                order by revision desc limit 1
                """ + (lock ? " for update" : ""))
                .param("orderId", orderId).param("sourceId", sourceId)
                .query((rs, row) -> map(rs)).optional();
    }

    static FillRecord map(ResultSet rs) throws SQLException {
        return new FillRecord(rs.getObject("fill_record_id", UUID.class), rs.getString("request_fingerprint"),
                rs.getObject("root_fill_id", UUID.class), rs.getObject("correction_of_record_id", UUID.class),
                rs.getObject("order_id", UUID.class), rs.getString("source_execution_id"), rs.getInt("revision"),
                FillRecordKind.valueOf(rs.getString("kind")), rs.getBigDecimal("quantity"), rs.getBigDecimal("price"),
                rs.getBigDecimal("commission"), rs.getBigDecimal("slippage"),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                rs.getObject("received_at", OffsetDateTime.class).toInstant());
    }

    private static FillRecordConflictException conflict(String message) { return new FillRecordConflictException(message); }
}
