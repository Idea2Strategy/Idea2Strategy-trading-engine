package com.idea2strategy.trading.persistence.intent;

import com.idea2strategy.trading.application.intent.OrderIntentBatchConflictException;
import com.idea2strategy.trading.application.port.OrderIntentBatchStore;
import com.idea2strategy.trading.domain.intent.IntentDecision;
import com.idea2strategy.trading.domain.intent.OrderIntent;
import com.idea2strategy.trading.domain.intent.OrderIntentBatch;
import com.idea2strategy.trading.domain.intent.OrderIntentOrigin;
import com.idea2strategy.trading.domain.intent.OrderIntentRequest;
import com.idea2strategy.trading.domain.order.OrderSide;
import com.idea2strategy.trading.domain.order.OrderType;
import com.idea2strategy.trading.domain.order.TimeInForce;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes the canonical {@code trading.order_intent_batches} and {@code trading.order_intents} rows.
 *
 * <p>The batch is written whole or not at all. Canonical
 * {@code intent_batch_finalized_complete} means a FINALIZED header without its intents is not a
 * partial result but a lie, and the unique index on {@code (bot_id, partition_id, source_event_id)}
 * is what makes the partition the isolation boundary: two partitions of the same official event get
 * their own batch, a redelivery of one partition does not.
 *
 * <p>Identity is derived, not allocated, so redelivery converges by re-deriving the same primary
 * keys and losing the insert race rather than by consulting a deduplication table.
 */
@Repository
public class PostgresOrderIntentBatchStore implements OrderIntentBatchStore {
    private static final String CONFLICT_MESSAGE = "Order intent batch identity conflict";
    private static final String CANONICAL_STATUS = "FINALIZED";

    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;

    public PostgresOrderIntentBatchStore(
            JdbcClient jdbcClient,
            PlatformTransactionManager transactionManager) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    @Override
    public OrderIntentBatch createOrLoad(OrderIntentBatch desired) {
        Objects.requireNonNull(desired, "desired");
        requireExactlyPersistable(desired);
        return transactionTemplate.execute(status -> createOrLoadInTransaction(desired));
    }

    private OrderIntentBatch createOrLoadInTransaction(OrderIntentBatch desired) {
        int inserted = jdbcClient.sql("""
                        insert into trading.order_intent_batches (
                            id, bot_id, partition_id, source_event_id, status,
                            conflict_policy_hash, composition_rules_version, input_state_hash,
                            result_hash, finalized_at
                        ) values (
                            :id, :botId, :partitionId, :sourceEventId, :status,
                            :conflictPolicyHash, :compositionRulesVersion, :inputStateHash,
                            :resultHash, :finalizedAt
                        )
                        on conflict do nothing
                        """)
                .param("id", desired.batchId())
                .param("botId", desired.botId())
                .param("partitionId", desired.partitionId())
                .param("sourceEventId", desired.sourceEventId())
                .param("status", CANONICAL_STATUS)
                .param("conflictPolicyHash", desired.conflictPolicyHash())
                .param("compositionRulesVersion", desired.compositionRulesVersion())
                .param("inputStateHash", desired.inputStateHash())
                .param("resultHash", desired.resultHash())
                .param("finalizedAt", offset(desired.finalizedAt()))
                .update();

        if (inserted == 1) {
            desired.intents().forEach(intent -> requireOne(insertIntent(desired, intent)));
            return desired;
        }

        OrderIntentBatch stored = load(desired).orElseThrow(PostgresOrderIntentBatchStore::conflict);
        if (!stored.equals(desired)) {
            throw conflict();
        }
        return stored;
    }

    private int insertIntent(OrderIntentBatch batch, OrderIntent intent) {
        OrderIntentRequest request = intent.request();
        return jdbcClient.sql("""
                        insert into trading.order_intents (
                            id, bot_id, batch_id, source_event_id, origin_type, evaluation_run_id,
                            partition_id, flow_id, instrument_id, intent_key, side, position_effect,
                            order_type, time_in_force, requested_quantity, post_netting_quantity,
                            final_quantity, limit_price, stop_price, requested_expires_at,
                            decision, decision_reason_code
                        ) values (
                            :id, :botId, :batchId, :sourceEventId,
                            cast(:originType as trading.intent_origin_type), :evaluationRunId,
                            :partitionId, :flowId, :instrumentId, :intentKey,
                            cast(:side as trading.order_side),
                            cast(:positionEffect as trading.position_effect),
                            cast(:orderType as trading.order_type),
                            cast(:timeInForce as trading.time_in_force),
                            :requestedQuantity, :postNettingQuantity, :finalQuantity,
                            :limitPrice, :stopPrice, :requestedExpiresAt,
                            cast(:decision as trading.intent_decision), :decisionReasonCode
                        )
                        on conflict do nothing
                        """)
                .param("id", intent.intentId())
                .param("botId", batch.botId())
                .param("batchId", batch.batchId())
                .param("sourceEventId", batch.sourceEventId())
                .param("originType", batch.origin().name())
                .param("evaluationRunId", batch.evaluationId())
                .param("partitionId", batch.partitionId())
                .param("flowId", request.flowId())
                .param("instrumentId", request.instrumentId())
                .param("intentKey", intent.intentKey())
                .param("side", request.side().name())
                .param("positionEffect", CanonicalIntentEnums.positionEffect(request.positionEffect()))
                .param("orderType", request.orderType().name())
                .param("timeInForce", request.timeInForce().name())
                .param("requestedQuantity", request.requestedQuantity())
                .param("postNettingQuantity", executedQuantity(request))
                .param("finalQuantity", request.finalQuantity())
                .param("limitPrice", request.limitPrice())
                .param("stopPrice", request.stopPrice())
                .param("requestedExpiresAt", offset(request.requestedExpiresAt()))
                .param("decision", request.decision().name())
                .param("decisionReasonCode", request.decisionReasonCode())
                .update();
    }

    /**
     * What survives netting as an actual order target. A non-executing decision leaves nothing, which
     * is the same zero the canonical column defaults to.
     */
    private static BigDecimal executedQuantity(OrderIntentRequest request) {
        return request.decision().executes() ? request.finalQuantity() : BigDecimal.ZERO;
    }

    private Optional<OrderIntentBatch> load(OrderIntentBatch desired) {
        try {
            return jdbcClient.sql("""
                            select id, bot_id, partition_id, source_event_id, status,
                                   conflict_policy_hash, composition_rules_version, input_state_hash,
                                   result_hash, finalized_at
                            from trading.order_intent_batches
                            where id = :id
                            """)
                    .param("id", desired.batchId())
                    .query((resultSet, rowNumber) -> new StoredHeader(
                            resultSet.getObject("id", UUID.class),
                            resultSet.getObject("bot_id", UUID.class),
                            resultSet.getObject("partition_id", UUID.class),
                            resultSet.getObject("source_event_id", UUID.class),
                            resultSet.getString("status"),
                            resultSet.getString("conflict_policy_hash"),
                            resultSet.getString("composition_rules_version"),
                            resultSet.getString("input_state_hash"),
                            resultSet.getString("result_hash"),
                            instant(resultSet.getObject("finalized_at", OffsetDateTime.class))))
                    .optional()
                    .map(header -> toDomain(header, desired.origin(), desired.evaluationId()));
        } catch (IllegalArgumentException invalidStoredState) {
            throw conflict(invalidStoredState);
        }
    }

    /**
     * Rebuilds the stored aggregate so the caller can compare it field by field.
     *
     * <p>The evaluation is carried over from the aggregate being compared rather than read back. The
     * canonical batch row has no evaluation column, and this row was located by a primary key derived
     * from that evaluation, so a row under this id belongs to it. {@link #requireIntentsRecord} still
     * checks the canonical {@code evaluation_run_id} the intents carry, so a batch whose intents name
     * a different evaluation is a conflict rather than a silent match.
     */
    private OrderIntentBatch toDomain(StoredHeader header, OrderIntentOrigin origin, UUID evaluationId) {
        if (!CANONICAL_STATUS.equals(header.status())) {
            throw conflict();
        }
        requireIntentsRecord(header.id(), evaluationId);
        return new OrderIntentBatch(
                header.id(),
                header.botId(),
                header.partitionId(),
                header.sourceEventId(),
                origin,
                evaluationId,
                header.inputStateHash(),
                header.conflictPolicyHash(),
                header.compositionRulesVersion(),
                header.resultHash(),
                header.finalizedAt(),
                loadIntents(header.id()));
    }

    /**
     * Every stored intent must name the evaluation this batch was derived from. An empty batch has
     * nothing to check, which is correct: an evaluation that produced no candidate still finalizes.
     */
    private void requireIntentsRecord(UUID batchId, UUID evaluationId) {
        List<UUID> distinct = jdbcClient.sql("""
                        select distinct evaluation_run_id
                        from trading.order_intents
                        where batch_id = :batchId
                        """)
                .param("batchId", batchId)
                .query(UUID.class)
                .list();
        if (distinct.size() > 1
                || (distinct.size() == 1
                        && !java.util.Objects.equals(evaluationId, distinct.getFirst()))) {
            throw conflict();
        }
    }

    private List<OrderIntent> loadIntents(UUID batchId) {
        return jdbcClient.sql("""
                        select id, flow_id, instrument_id, intent_key, side, position_effect,
                               order_type, time_in_force, requested_quantity, final_quantity,
                               limit_price, stop_price, requested_expires_at, decision,
                               decision_reason_code
                        from trading.order_intents
                        where batch_id = :batchId
                        order by intent_key
                        """)
                .param("batchId", batchId)
                .query((resultSet, rowNumber) -> new OrderIntent(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("intent_key"),
                        new OrderIntentRequest(
                                candidateOf(resultSet.getString("intent_key")),
                                resultSet.getObject("flow_id", UUID.class),
                                resultSet.getObject("instrument_id", UUID.class),
                                OrderSide.valueOf(resultSet.getString("side")),
                                CanonicalIntentEnums.positionEffect(resultSet.getString("position_effect")),
                                OrderType.valueOf(resultSet.getString("order_type")),
                                TimeInForce.valueOf(resultSet.getString("time_in_force")),
                                resultSet.getBigDecimal("requested_quantity"),
                                resultSet.getBigDecimal("limit_price"),
                                resultSet.getBigDecimal("stop_price"),
                                instant(resultSet.getObject("requested_expires_at", OffsetDateTime.class)),
                                IntentDecision.valueOf(resultSet.getString("decision")),
                                resultSet.getString("decision_reason_code"),
                                resultSet.getBigDecimal("final_quantity"))))
                .list();
    }

    private static UUID candidateOf(String intentKey) {
        return UUID.fromString(intentKey.substring("candidate:".length()));
    }

    /**
     * PostgreSQL would silently round a value that does not fit the canonical column, and the stored
     * row would then no longer equal the aggregate a redelivery re-derives. Refusing here keeps the
     * failure at the boundary that caused it.
     */
    private static void requireExactlyPersistable(OrderIntentBatch batch) {
        for (OrderIntent intent : batch.intents()) {
            OrderIntentRequest request = intent.request();
            requireScale(request.requestedQuantity(), 28, "requestedQuantity");
            requireScale(request.finalQuantity(), 28, "finalQuantity");
            requireScale(request.limitPrice(), 24, "limitPrice");
            requireScale(request.stopPrice(), 24, "stopPrice");
        }
    }

    private static void requireScale(BigDecimal value, int precision, String name) {
        if (value == null) {
            return;
        }
        BigDecimal atCanonicalScale;
        try {
            atCanonicalScale = value.setScale(8, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException notExactlyRepresentable) {
            throw new IllegalArgumentException(
                    name + " exceeds the canonical scale of 8", notExactlyRepresentable);
        }
        if (atCanonicalScale.precision() > precision) {
            throw new IllegalArgumentException(name + " exceeds the canonical precision of " + precision);
        }
    }

    private static void requireOne(int inserted) {
        if (inserted != 1) {
            throw conflict();
        }
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime offset(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static OrderIntentBatchConflictException conflict() {
        return new OrderIntentBatchConflictException(CONFLICT_MESSAGE);
    }

    private static OrderIntentBatchConflictException conflict(Throwable cause) {
        return new OrderIntentBatchConflictException(CONFLICT_MESSAGE, cause);
    }

    private record StoredHeader(
            UUID id,
            UUID botId,
            UUID partitionId,
            UUID sourceEventId,
            String status,
            String conflictPolicyHash,
            String compositionRulesVersion,
            String inputStateHash,
            String resultHash,
            Instant finalizedAt) {
    }
}
