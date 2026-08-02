package com.idea2strategy.trading.persistence.shorting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.idea2strategy.trading.application.shorting.BorrowFeeAccrualConflictException;
import com.idea2strategy.trading.domain.shorting.BorrowFeeAccrual;
import com.idea2strategy.trading.domain.shorting.BorrowFeeAccrualRequest;
import com.idea2strategy.trading.domain.shorting.BorrowFeePolicy;
import com.idea2strategy.trading.domain.shorting.DayCountConvention;
import com.idea2strategy.trading.domain.shorting.OpenShortLotSnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class BorrowFeeAccrualPersistenceTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    private static DriverManagerDataSource dataSource;
    private static JdbcClient jdbc;

    @BeforeAll
    static void setup() {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = JdbcClient.create(dataSource);
        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    @BeforeEach
    void clear() {
        jdbc.sql("truncate table trading.short_borrow_fee_accrual").update();
    }

    @Test
    void exactRetrySurvivesStoreRestartAndJooqReadsAllPolicyEvidence() {
        BorrowFeeAccrual accrual = accrual();
        assertEquals(accrual, newStore().appendOrLoad(accrual));
        assertEquals(accrual, newStore().appendOrLoad(accrual));

        var query = new JooqBorrowFeeAccrualQuery(DSL.using(dataSource, SQLDialect.POSTGRES));
        assertEquals(accrual, query.findById(accrual.accrualId()).orElseThrow().toDomain());
        assertEquals(1, query.findByLot(accrual.lotId()).size());
    }

    @Test
    void identityCollisionWithDifferentEvidenceIsRejectedWithoutMutation() {
        BorrowFeeAccrual accrual = accrual();
        newStore().appendOrLoad(accrual);
        BorrowFeeAccrual conflicting = new BorrowFeeAccrual(
                accrual.accrualId(), accrual.lotId(), accrual.instrumentId(), accrual.botId(), accrual.accrualDate(),
                accrual.openQuantity(), accrual.referencePrice(), accrual.notionalAmount(),
                new BigDecimal("0.99"), accrual.feeAmount(), accrual.currency(), accrual.dayCountConvention(),
                accrual.policyVersion(), accrual.rateSnapshotVersion(), accrual.accruedAt());

        assertThrows(BorrowFeeAccrualConflictException.class, () -> newStore().appendOrLoad(conflicting));
        assertEquals(accrual, new JooqBorrowFeeAccrualQuery(DSL.using(dataSource, SQLDialect.POSTGRES))
                .findById(accrual.accrualId()).orElseThrow().toDomain());
    }

    @Test
    void oneLotDayCannotBeChargedTwiceUnderAChangedPolicyVersion() {
        BorrowFeeAccrual accrual = accrual();
        newStore().appendOrLoad(accrual);
        BorrowFeeAccrual changedPolicy = new BorrowFeeAccrual(
                UUID.randomUUID(), accrual.lotId(), accrual.instrumentId(), accrual.botId(), accrual.accrualDate(),
                accrual.openQuantity(), accrual.referencePrice(), accrual.notionalAmount(), accrual.annualBorrowRate(),
                accrual.feeAmount(), accrual.currency(), accrual.dayCountConvention(), "policy-v4",
                accrual.rateSnapshotVersion(), accrual.accruedAt());

        assertThrows(BorrowFeeAccrualConflictException.class, () -> newStore().appendOrLoad(changedPolicy));
        assertEquals(1, new JooqBorrowFeeAccrualQuery(DSL.using(dataSource, SQLDialect.POSTGRES))
                .findByLot(accrual.lotId()).size());
    }

    private static PostgresBorrowFeeAccrualStore newStore() {
        return new PostgresBorrowFeeAccrualStore(JdbcClient.create(dataSource));
    }

    private static BorrowFeeAccrual accrual() {
        var lot = new OpenShortLotSnapshot(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("20"), new BigDecimal("50"), new BigDecimal("0.05"), "rate-v17",
                Instant.parse("2026-07-01T00:00:00Z"));
        return BorrowFeeAccrual.calculate(new BorrowFeeAccrualRequest(lot, LocalDate.parse("2026-08-01"),
                Instant.parse("2026-08-02T00:00:00Z"), new BorrowFeePolicy(
                "policy-v3", DayCountConvention.ACTUAL_360, 4, RoundingMode.HALF_UP, "USD")));
    }
}
