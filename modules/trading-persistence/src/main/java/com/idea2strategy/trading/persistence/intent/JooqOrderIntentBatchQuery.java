package com.idea2strategy.trading.persistence.intent;

import com.idea2strategy.trading.domain.intent.OrderIntentIdentity;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
public class JooqOrderIntentBatchQuery {
    private final DSLContext dsl;

    public JooqOrderIntentBatchQuery(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    public int countBatches() {
        return dsl.fetchCount(dsl.selectFrom("trading.order_intent_batch"));
    }

    public int countMappings() {
        return dsl.fetchCount(dsl.selectFrom("trading.order_intent_identity"));
    }

    public Optional<OrderIntentBatchPersistenceView> findByEvaluationId(UUID evaluationId) {
        return dsl.fetchOptional("""
                        select batch_id, bot_id, evaluation_id, source_candidate_batch_id, request_fingerprint
                        from trading.order_intent_batch
                        where evaluation_id = ?
                        """, Objects.requireNonNull(evaluationId, "evaluationId"))
                .map(this::toView);
    }

    private OrderIntentBatchPersistenceView toView(Record header) {
        UUID batchId = header.get("batch_id", UUID.class);
        var intents = dsl.fetch("""
                        select intent_id, candidate_id
                        from trading.order_intent_identity
                        where batch_id = ?
                        order by ordinal
                        """, batchId)
                .map(record -> new OrderIntentIdentity(
                        record.get("intent_id", UUID.class),
                        record.get("candidate_id", UUID.class)));
        return new OrderIntentBatchPersistenceView(
                batchId,
                header.get("bot_id", UUID.class),
                header.get("evaluation_id", UUID.class),
                header.get("source_candidate_batch_id", UUID.class),
                header.get("request_fingerprint", String.class),
                intents);
    }
}
