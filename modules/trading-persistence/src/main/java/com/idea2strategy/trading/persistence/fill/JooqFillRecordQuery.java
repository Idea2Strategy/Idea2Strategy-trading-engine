package com.idea2strategy.trading.persistence.fill;

import com.idea2strategy.trading.domain.fill.FillRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;

public final class JooqFillRecordQuery {
    private final DSLContext dsl;
    public JooqFillRecordQuery(DSLContext dsl) { this.dsl = dsl; }

    public List<FillRecord> history(UUID orderId) {
        return dsl.fetch("""
                select * from trading.execution_fill_record
                where order_id = ? order by occurred_at, source_execution_id, revision
                """, orderId).map(this::toDomain);
    }

    public long tradeCount(UUID orderId) {
        Long value = dsl.fetchOne("""
                select count(*) from trading.execution_fill_record
                where order_id = ? and revision = 0 and kind = 'ORIGINAL'
                """, orderId).get(0, Long.class);
        return value == null ? 0 : value;
    }

    public Optional<EffectiveFillView> effective(UUID orderId, String sourceExecutionId) {
        return dsl.fetchOptional("""
                select fill_record_id, root_fill_id, revision, kind, quantity, price, commission, slippage,
                       occurred_at, received_at
                from trading.execution_fill_record
                where order_id = ? and source_execution_id = ? order by revision desc limit 1
                """, orderId, sourceExecutionId).map(row -> new EffectiveFillView(
                row.get("fill_record_id", UUID.class), row.get("root_fill_id", UUID.class),
                row.get("revision", Integer.class), row.get("kind", String.class),
                row.get("quantity", java.math.BigDecimal.class), row.get("price", java.math.BigDecimal.class),
                row.get("commission", java.math.BigDecimal.class), row.get("slippage", java.math.BigDecimal.class),
                row.get("occurred_at", java.time.OffsetDateTime.class).toInstant(),
                row.get("received_at", java.time.OffsetDateTime.class).toInstant()));
    }

    private FillRecord toDomain(Record row) {
        return new FillRecord(row.get("fill_record_id", UUID.class), row.get("request_fingerprint", String.class),
                row.get("root_fill_id", UUID.class), row.get("correction_of_record_id", UUID.class),
                row.get("order_id", UUID.class), row.get("source_execution_id", String.class),
                row.get("revision", Integer.class),
                com.idea2strategy.trading.domain.fill.FillRecordKind.valueOf(row.get("kind", String.class)),
                row.get("quantity", java.math.BigDecimal.class), row.get("price", java.math.BigDecimal.class),
                row.get("commission", java.math.BigDecimal.class), row.get("slippage", java.math.BigDecimal.class),
                row.get("occurred_at", java.time.OffsetDateTime.class).toInstant(),
                row.get("received_at", java.time.OffsetDateTime.class).toInstant());
    }

    public record EffectiveFillView(UUID fillRecordId, UUID rootFillId, int revision, String kind,
                                    java.math.BigDecimal quantity, java.math.BigDecimal price,
                                    java.math.BigDecimal commission, java.math.BigDecimal slippage,
                                    java.time.Instant occurredAt, java.time.Instant receivedAt) {}
}
