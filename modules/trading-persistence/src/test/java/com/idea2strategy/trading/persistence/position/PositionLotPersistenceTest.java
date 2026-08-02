package com.idea2strategy.trading.persistence.position;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.idea2strategy.trading.application.position.CloseLongPositionCommand;
import com.idea2strategy.trading.application.position.OpenPositionLotCommand;
import com.idea2strategy.trading.application.position.PositionConflictException;
import com.idea2strategy.trading.application.position.PositionMutationResult;
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
class PositionLotPersistenceTest {
    private static final UUID BOT=UUID.randomUUID(),PARTITION=UUID.randomUUID(),FLOW=UUID.randomUUID(),INSTRUMENT=UUID.randomUUID();
    private static final Instant T0=Instant.parse("2026-08-02T14:30:00Z");
    @Container private static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17-alpine");
    private static DriverManagerDataSource dataSource;private static JdbcClient jdbc;private static JdbcTransactionManager transactions;private static PostgresPositionLotStore store;private static JooqPositionLotQuery query;
    @BeforeAll static void setup(){dataSource=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());Flyway.configure().dataSource(dataSource).load().migrate();jdbc=JdbcClient.create(dataSource);transactions=new JdbcTransactionManager(dataSource);store=newStore();query=new JooqPositionLotQuery(DSL.using(dataSource,SQLDialect.POSTGRES));}
    @BeforeEach void clear(){jdbc.sql("truncate table trading.execution_position_lot cascade").update();jdbc.sql("truncate table trading.execution_flow_position_projection, trading.execution_position_command cascade").update();}

    @Test void fifoFractionalCloseKeepsLotsProjectionAndRealizedPnlConsistentAcrossRestart(){
        UUID firstFill=UUID.randomUUID(),secondFill=UUID.randomUUID(),sellFill=UUID.randomUUID();
        store.open(open(firstFill,"1","100","0.20",T0));
        store.open(open(secondFill,"2","110","0.44",T0.plusSeconds(1)));
        CloseLongPositionCommand close=new CloseLongPositionCommand(BOT,PARTITION,FLOW,INSTRUMENT,sellFill,new BigDecimal("1.5"),new BigDecimal("120"),new BigDecimal("0.36"),T0.plusSeconds(2));
        PositionMutationResult result=store.closeLong(close);
        assertEquals(result,newStore().closeLong(close));
        assertDecimal("24.33",result.realizedPnl());assertDecimal("1.5",result.endingQuantity());assertDecimal("165.33",result.endingCostBasis());assertEquals(2,result.affectedLots());
        var lots=query.lots(BOT,PARTITION,FLOW,INSTRUMENT);assertDecimal("0",lots.get(0).remainingQuantity());assertDecimal("1.5",lots.get(1).remainingQuantity());
        assertEquals(2,query.movements(sellFill).size());assertDecimal("24.33",query.flow(BOT,PARTITION,FLOW,INSTRUMENT).orElseThrow().realizedPnl());
    }

    @Test void overCloseAndConflictingFillReplayRollBackWithoutMutatingPosition(){
        UUID buy=UUID.randomUUID();store.open(open(buy,"1","10","0.02",T0));
        assertThrows(PositionConflictException.class,()->store.closeLong(new CloseLongPositionCommand(BOT,PARTITION,FLOW,INSTRUMENT,UUID.randomUUID(),new BigDecimal("1.1"),new BigDecimal("11"),new BigDecimal("0.02"),T0.plusSeconds(1))));
        assertThrows(PositionConflictException.class,()->store.open(open(buy,"2","10","0.04",T0)));
        assertDecimal("1",query.flow(BOT,PARTITION,FLOW,INSTRUMENT).orElseThrow().quantity());assertEquals(1,query.lots(BOT,PARTITION,FLOW,INSTRUMENT).size());
    }

    @Test void completeCloseLeavesNoRoundingResidue(){
        store.open(open(UUID.randomUUID(),"3","10","0.01",T0));
        PositionMutationResult result=store.closeLong(new CloseLongPositionCommand(BOT,PARTITION,FLOW,INSTRUMENT,UUID.randomUUID(),new BigDecimal("3"),new BigDecimal("12"),new BigDecimal("0.02"),T0.plusSeconds(1)));
        assertDecimal("0",result.endingQuantity());assertDecimal("0",result.endingCostBasis());assertEquals(true,query.lots(BOT,PARTITION,FLOW,INSTRUMENT).get(0).closedAt().isPresent());
    }
    private static OpenPositionLotCommand open(UUID fill,String q,String p,String fee,Instant at){return new OpenPositionLotCommand(BOT,PARTITION,FLOW,INSTRUMENT,fill,new BigDecimal(q),new BigDecimal(p),new BigDecimal(fee),at);}
    private static void assertDecimal(String expected,BigDecimal actual){assertEquals(0,new BigDecimal(expected).compareTo(actual));}
    private static PostgresPositionLotStore newStore(){return new PostgresPositionLotStore(JdbcClient.create(dataSource),transactions);}
}
