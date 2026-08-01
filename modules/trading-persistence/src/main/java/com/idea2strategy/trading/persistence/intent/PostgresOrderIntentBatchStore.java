package com.idea2strategy.trading.persistence.intent;

import com.idea2strategy.trading.application.intent.OrderIntentBatchConflictException;
import com.idea2strategy.trading.application.port.OrderIntentBatchStore;
import com.idea2strategy.trading.domain.intent.OrderIntentBatch;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class PostgresOrderIntentBatchStore implements OrderIntentBatchStore {
    private static final String CONFLICT_MESSAGE = "Order intent batch identity conflict";

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
        try {
            return transactionTemplate.execute(status -> createOrLoadInTransaction(desired));
        } catch (DataAccessException databaseFailure) {
            throw conflict(databaseFailure);
        }
    }

    private OrderIntentBatch createOrLoadInTransaction(OrderIntentBatch desired) {
        int inserted = jdbcClient.sql("""
                        insert into trading.order_intent_batch (
                            batch_id,
                            evaluation_id,
                            bot_id,
                            source_candidate_batch_id,
                            request_fingerprint
                        ) values (
                            :batchId,
                            :evaluationId,
                            :botId,
                            :sourceCandidateBatchId,
                            :requestFingerprint
                        )
                        on conflict do nothing
                        """)
                .param("batchId", desired.batchId())
                .param("evaluationId", desired.evaluationId())
                .param("botId", desired.botId())
                .param("sourceCandidateBatchId", desired.sourceCandidateBatchId())
                .param("requestFingerprint", desired.requestFingerprint())
                .update();
        if (inserted == 1) {
            insertMappings(desired);
            return desired;
        }

        OrderIntentBatch stored = loadByEvaluationId(desired.evaluationId())
                .orElseThrow(PostgresOrderIntentBatchStore::conflict);
        if (!stored.equals(desired)) {
            throw conflict();
        }
        return stored;
    }

    private void insertMappings(OrderIntentBatch desired) {
        for (int ordinal = 0; ordinal < desired.intents().size(); ordinal++) {
            var identity = desired.intents().get(ordinal);
            int inserted = jdbcClient.sql("""
                            insert into trading.order_intent_identity (
                                intent_id,
                                batch_id,
                                candidate_id,
                                ordinal
                            ) values (
                                :intentId,
                                :batchId,
                                :candidateId,
                                :ordinal
                            )
                            on conflict do nothing
                            """)
                    .param("intentId", identity.intentId())
                    .param("batchId", desired.batchId())
                    .param("candidateId", identity.candidateId())
                    .param("ordinal", ordinal)
                    .update();
            if (inserted != 1) {
                throw conflict();
            }
        }
    }

    private Optional<OrderIntentBatch> loadByEvaluationId(UUID evaluationId) {
        try {
            return jdbcClient.sql("""
                            select batch_id, bot_id, evaluation_id, source_candidate_batch_id, request_fingerprint
                            from trading.order_intent_batch
                            where evaluation_id = :evaluationId
                    """)
                    .param("evaluationId", evaluationId)
                    .query((resultSet, rowNumber) -> new StoredHeader(
                            resultSet.getObject("batch_id", UUID.class),
                            resultSet.getObject("bot_id", UUID.class),
                            resultSet.getObject("evaluation_id", UUID.class),
                            resultSet.getObject("source_candidate_batch_id", UUID.class),
                            resultSet.getString("request_fingerprint")))
                    .optional()
                    .map(header -> new OrderIntentBatchPersistenceView(
                            header.batchId(),
                            header.botId(),
                            header.evaluationId(),
                            header.sourceCandidateBatchId(),
                            header.requestFingerprint(),
                            loadMappings(header.batchId())))
                    .map(OrderIntentBatchPersistenceView::toDomain);
        } catch (IllegalArgumentException invalidStoredState) {
            throw conflict(invalidStoredState);
        }
    }

    private List<OrderIntentBatchPersistenceView.Mapping> loadMappings(UUID batchId) {
        return jdbcClient.sql("""
                        select ordinal, intent_id, candidate_id
                        from trading.order_intent_identity
                        where batch_id = :batchId
                        order by ordinal
                        """)
                .param("batchId", batchId)
                .query((resultSet, rowNumber) -> new OrderIntentBatchPersistenceView.Mapping(
                        resultSet.getInt("ordinal"),
                        resultSet.getObject("intent_id", UUID.class),
                        resultSet.getObject("candidate_id", UUID.class)))
                .list();
    }

    private static OrderIntentBatchConflictException conflict() {
        return new OrderIntentBatchConflictException(CONFLICT_MESSAGE);
    }

    private static OrderIntentBatchConflictException conflict(Throwable cause) {
        return new OrderIntentBatchConflictException(CONFLICT_MESSAGE, cause);
    }

    private record StoredHeader(
            UUID batchId,
            UUID botId,
            UUID evaluationId,
            UUID sourceCandidateBatchId,
            String requestFingerprint) {
    }
}
