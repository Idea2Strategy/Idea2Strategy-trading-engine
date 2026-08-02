package com.idea2strategy.trading.persistence.intent;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

/** Reads the canonical intent tables through the jOOQ query boundary. */
@Repository
public class JooqOrderIntentBatchQuery {
    private final DSLContext dsl;

    public JooqOrderIntentBatchQuery(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    public int countBatches() {
        return dsl.fetchCount(dsl.selectFrom("trading.order_intent_batches"));
    }

    public int countIntents() {
        return dsl.fetchCount(dsl.selectFrom("trading.order_intents"));
    }

    public Optional<OrderIntentBatchPersistenceView> findBatch(UUID batchId) {
        Objects.requireNonNull(batchId, "batchId");
        return dsl.fetchOptional("""
                        select id, bot_id, partition_id, source_event_id, status,
                               conflict_policy_hash, composition_rules_version, input_state_hash,
                               result_hash, finalized_at
                        from trading.order_intent_batches
                        where id = ?
                        """, batchId)
                .map(this::toView);
    }

    private OrderIntentBatchPersistenceView toView(Record header) {
        UUID batchId = header.get("id", UUID.class);
        // Ordered by the canonical per-batch uniqueness handle so the projection is stable.
        var intents = dsl.fetch("""
                        select id, intent_key, batch_id, bot_id, partition_id, source_event_id,
                               evaluation_run_id, flow_id, instrument_id, origin_type, side,
                               position_effect, order_type, time_in_force, requested_quantity,
                               post_netting_quantity, final_quantity, limit_price, decision,
                               decision_reason_code
                        from trading.order_intents
                        where batch_id = ?
                        order by intent_key
                        """, batchId)
                .map(record -> new OrderIntentBatchPersistenceView.IntentRow(
                        record.get("id", UUID.class),
                        record.get("intent_key", String.class),
                        record.get("batch_id", UUID.class),
                        record.get("bot_id", UUID.class),
                        record.get("partition_id", UUID.class),
                        record.get("source_event_id", UUID.class),
                        record.get("evaluation_run_id", UUID.class),
                        record.get("flow_id", UUID.class),
                        record.get("instrument_id", UUID.class),
                        record.get("origin_type", String.class),
                        record.get("side", String.class),
                        record.get("position_effect", String.class),
                        record.get("order_type", String.class),
                        record.get("time_in_force", String.class),
                        record.get("requested_quantity", BigDecimal.class),
                        record.get("post_netting_quantity", BigDecimal.class),
                        record.get("final_quantity", BigDecimal.class),
                        record.get("limit_price", BigDecimal.class),
                        record.get("decision", String.class),
                        record.get("decision_reason_code", String.class)));
        return new OrderIntentBatchPersistenceView(
                batchId,
                header.get("bot_id", UUID.class),
                header.get("partition_id", UUID.class),
                header.get("source_event_id", UUID.class),
                header.get("status", String.class),
                header.get("conflict_policy_hash", String.class),
                header.get("composition_rules_version", String.class),
                header.get("input_state_hash", String.class),
                header.get("result_hash", String.class),
                instant(header.get("finalized_at", OffsetDateTime.class)),
                intents);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
