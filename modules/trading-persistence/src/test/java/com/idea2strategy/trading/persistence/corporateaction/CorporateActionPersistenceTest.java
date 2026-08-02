package com.idea2strategy.trading.persistence.corporateaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.idea2strategy.trading.application.corporateaction.CorporateActionConflictException;
import com.idea2strategy.trading.application.position.OpenPositionLotCommand;
import com.idea2strategy.trading.domain.corporateaction.ApprovedCorporateAction;
import com.idea2strategy.trading.domain.corporateaction.CorporateActionApprovalStatus;
import com.idea2strategy.trading.domain.corporateaction.CorporateActionType;
import com.idea2strategy.trading.persistence.position.JooqPositionLotQuery;
import com.idea2strategy.trading.persistence.position.PostgresPositionLotStore;
import java.math.BigDecimal;
import java.time.Instant;
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

@Testcontainers(disabledWithoutDocker=true)
class CorporateActionPersistenceTest {
    private static final UUID BOT=UUID.randomUUID(),PARTITION=UUID.randomUUID(),FLOW=UUID.randomUUID(),INSTRUMENT=UUID.randomUUID();
    private static final Instant T0=Instant.parse("2026-08-01T14:30:00Z");
    @Container private static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17-alpine");
    private static DriverManagerDataSource dataSource;private static JdbcClient jdbc;private static JdbcTransactionManager tx;private static PostgresCorporateActionStore store;private static PostgresPositionLotStore positions;private static JooqPositionLotQuery query;
    @BeforeAll static void setup(){dataSource=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());Flyway.configure().dataSource(dataSource).load().migrate();jdbc=JdbcClient.create(dataSource);tx=new JdbcTransactionManager(dataSource);store=new PostgresCorporateActionStore(jdbc,tx);positions=new PostgresPositionLotStore(jdbc,tx);query=new JooqPositionLotQuery(DSL.using(dataSource,SQLDialect.POSTGRES));}
    @BeforeEach void clear(){jdbc.sql("truncate table trading.execution_corporate_action_application, trading.execution_position_lot, trading.execution_flow_position_projection, trading.execution_position_command cascade").update();}

    @Test void approvedSplitAdjustsEveryOpenLotAndFlowExactlyOnceWhilePreservingBasis(){
        positions.open(new OpenPositionLotCommand(BOT,PARTITION,FLOW,INSTRUMENT,UUID.randomUUID(),new BigDecimal("1.5"),new BigDecimal("100"),new BigDecimal("0.30"),T0));
        ApprovedCorporateAction action=action(UUID.randomUUID(),2,1);
        var result=store.apply(action);assertEquals(result,new PostgresCorporateActionStore(JdbcClient.create(dataSource),tx).apply(action));
        assertEquals(1,result.adjustedLots());assertEquals(1,result.adjustedFlowPositions());assertEquals(PostgresCorporateActionStore.NO_MONETARY_POSTING,result.ledgerEffect());
        var lot=query.lots(BOT,PARTITION,FLOW,INSTRUMENT).get(0);decimal("3",lot.remainingQuantity());decimal("150.30",lot.remainingCostBasis());
        var flow=query.flow(BOT,PARTITION,FLOW,INSTRUMENT).orElseThrow();decimal("3",flow.quantity());decimal("150.30",flow.costBasis());
        assertEquals(1,jdbc.sql("select count(*) from trading.execution_corporate_action_lot_adjustment").query(Integer.class).single());
    }

    @Test void unrepresentableRatioRollsBackInsteadOfRoundingAndIdentityConflictDoesNotReapply(){
        positions.open(new OpenPositionLotCommand(BOT,PARTITION,FLOW,INSTRUMENT,UUID.randomUUID(),BigDecimal.ONE,BigDecimal.TEN,new BigDecimal("0.02"),T0));
        ApprovedCorporateAction unsupported=action(UUID.randomUUID(),1,3);
        assertThrows(ArithmeticException.class,()->store.apply(unsupported));
        assertEquals(0,jdbc.sql("select count(*) from trading.execution_corporate_action_application").query(Integer.class).single());
        ApprovedCorporateAction accepted=action(UUID.randomUUID(),2,1);store.apply(accepted);
        ApprovedCorporateAction conflict=new ApprovedCorporateAction(accepted.actionId(),INSTRUMENT,CorporateActionType.SPLIT,3,1,accepted.effectiveAt(),CorporateActionApprovalStatus.APPROVED,accepted.approvalId(),accepted.approvedByOperatorId(),accepted.approvedAt(),accepted.evidenceDigest(),accepted.policyVersion());
        assertThrows(CorporateActionConflictException.class,()->store.apply(conflict));
        decimal("2",query.flow(BOT,PARTITION,FLOW,INSTRUMENT).orElseThrow().quantity());
    }

    @Test void domainRejectsAiCandidateOrPendingApprovalBeforePersistence(){
        assertThrows(IllegalArgumentException.class,()->new ApprovedCorporateAction(UUID.randomUUID(),INSTRUMENT,CorporateActionType.SPLIT,2,1,T0.plusSeconds(2),CorporateActionApprovalStatus.PENDING,UUID.randomUUID(),UUID.randomUUID(),T0,"a".repeat(64),"split-v1"));
    }
    private static ApprovedCorporateAction action(UUID id,long numerator,long denominator){return new ApprovedCorporateAction(id,INSTRUMENT,CorporateActionType.SPLIT,numerator,denominator,T0.plusSeconds(10),CorporateActionApprovalStatus.APPROVED,UUID.randomUUID(),UUID.randomUUID(),T0,"a".repeat(64),"split-v1");}
    private static void decimal(String expected,BigDecimal actual){assertEquals(0,new BigDecimal(expected).compareTo(actual));}
}
