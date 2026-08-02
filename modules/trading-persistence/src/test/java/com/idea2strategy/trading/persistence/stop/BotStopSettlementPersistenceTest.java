package com.idea2strategy.trading.persistence.stop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.idea2strategy.trading.application.stop.BotStopOrchestrator;
import com.idea2strategy.trading.application.stop.RequestBotStopCommand;
import com.idea2strategy.trading.application.stop.StopStepResult;
import com.idea2strategy.trading.domain.stop.BotStopSettlement;
import com.idea2strategy.trading.domain.stop.StopCheckpoint;
import com.idea2strategy.trading.domain.stop.StopReason;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class BotStopSettlementPersistenceTest {
    private static final Instant T0 = Instant.parse("2026-08-02T03:00:00Z");
    @Container private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    private static DriverManagerDataSource dataSource;
    private static JdbcClient jdbc;
    private static JooqBotStopSettlementQuery query;

    @BeforeAll
    static void setup() {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = JdbcClient.create(dataSource);
        query = new JooqBotStopSettlementQuery(DSL.using(dataSource, SQLDialect.POSTGRES));
    }

    @BeforeEach
    void clear() {
        jdbc.sql("truncate table trading.bot_stop_settlement cascade").update();
    }

    @Test
    void persistsEveryCheckpointAndContinuesPartialLiquidationAfterRestart() {
        UUID botId = UUID.randomUUID();
        Queue<StopStepResult> liquidation = new ArrayDeque<>();
        liquidation.add(StopStepResult.partial("fractional position remains"));
        liquidation.add(StopStepResult.completed("flat"));
        PostgresBotStopSettlementStore firstStore = store();
        BotStopOrchestrator first = new BotStopOrchestrator(firstStore,
                (id, op) -> StopStepResult.completed("blocked"),
                (id, op) -> StopStepResult.completed("cancelled and released"),
                (id, op) -> liquidation.remove());

        BotStopSettlement pending = first.requestStop(new RequestBotStopCommand(
                botId, StopReason.ACCOUNT_SUSPENDED, "account suspended", T0));
        assertEquals(StopCheckpoint.LIQUIDATING, pending.checkpoint());

        BotStopOrchestrator restarted = new BotStopOrchestrator(store(),
                (id, op) -> { throw new AssertionError("gate must not replay"); },
                (id, op) -> { throw new AssertionError("cleanup must not replay"); },
                (id, op) -> liquidation.remove());
        BotStopSettlement stopped = restarted.resumeRecoverable(T0.plusSeconds(1)).getFirst();

        assertEquals(StopCheckpoint.STOPPED, stopped.checkpoint());
        assertEquals(stopped, query.find(stopped.settlementId()).orElseThrow().toDomain());
        assertEquals(4, query.attempts(stopped.settlementId()).size());
        assertEquals(StopReason.ACCOUNT_SUSPENDED, stopped.reason());
    }

    @Test
    void repeatedRequestAndRetryUseOneDurableSettlementAndStableOperationId() {
        UUID botId = UUID.randomUUID();
        PostgresBotStopSettlementStore store = store();
        BotStopSettlement desired = BotStopSettlement.request(botId, StopReason.POLICY_FORCED, "policy", T0);
        BotStopSettlement first = store.createOrLoad(desired);
        BotStopSettlement second = store.createOrLoad(desired);
        StopStepResult retry = StopStepResult.retryable("temporary");
        BotStopSettlement pending = store.recordStep(first, first.nextStep(), retry, T0.plusSeconds(1));

        assertEquals(first.settlementId(), second.settlementId());
        assertEquals(first.operationId(first.nextStep()), pending.operationId(pending.nextStep()));
        assertEquals(1, query.attempts(first.settlementId()).size());
    }

    private static PostgresBotStopSettlementStore store() {
        return new PostgresBotStopSettlementStore(jdbc, new JdbcTransactionManager(dataSource));
    }
}
