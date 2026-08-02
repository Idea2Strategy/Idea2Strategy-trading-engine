package com.idea2strategy.trading.persistence.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idea2strategy.trading.application.policy.MissingTradingPolicyException;
import com.idea2strategy.trading.domain.policy.EffectiveTradingPolicy;
import com.idea2strategy.trading.persistence.canonical.CanonicalBaseline;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves policy resolution against the real canonical tables.
 *
 * <p>The canonical model pins the trading fee at 20 bps with a CHECK but leaves the buying power
 * buffer and the short risk rules open, so the registry reads whichever version is published rather
 * than deriving a value. A moment with no published version must fail rather than fall back, because
 * the canonical foreign keys are NOT NULL and a fill pins the policy id forever.
 */
@Testcontainers(disabledWithoutDocker = true)
class TradingPolicyRegistryTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final Instant BEFORE_ANY = Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant FIRST_FROM = Instant.parse("2026-07-10T00:00:00Z");
    private static final Instant SECOND_FROM = Instant.parse("2026-08-01T00:00:00Z");

    private static final UUID FEE_V1 = UUID.fromString("f1000000-0000-4000-8000-000000000001");
    private static final UUID FEE_V2 = UUID.fromString("f1000000-0000-4000-8000-000000000002");

    private static JdbcClient jdbc;
    private static PostgresTradingPolicyRegistry registry;

    @BeforeAll
    static void migrateAndPublish() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        CanonicalBaseline.migrateWithContributions(dataSource);
        jdbc = JdbcClient.create(dataSource);
        registry = new PostgresTradingPolicyRegistry(jdbc);

        // A superseded fee version and the open ended one that replaced it.
        publishFee(FEE_V1, "1.0.0", FIRST_FROM, SECOND_FROM, "a");
        publishFee(FEE_V2, "2.0.0", SECOND_FROM, null, "b");

        jdbc.sql("""
                insert into trading.buying_power_buffer_policy_versions (id, policy_code, version,
                    buffer_bps, rounding_rules_version, rules_hash, effective_from, published_at)
                values (gen_random_uuid(), 'OFFICIAL_BUFFER', '1.0.0', 25, '1.0.0', ?, ?, ?)
                """).param("c".repeat(64)).param(at(FIRST_FROM)).param(at(FIRST_FROM)).update();

        jdbc.sql("""
                insert into trading.short_risk_policy_versions (id, policy_code, version,
                    rules_document, rules_hash, effective_from, published_at)
                values (gen_random_uuid(), 'OFFICIAL_SHORT_RISK', '1.0.0',
                    cast(? as jsonb), ?, ?, ?)
                """).param("{\"maintenanceBps\":3000}").param("d".repeat(64))
                .param(at(FIRST_FROM)).param(at(FIRST_FROM)).update();

        jdbc.sql("""
                insert into trading.short_borrow_fee_policy_versions (id, policy_code, version,
                    annual_fee_rate_bps, day_count_basis, calculation_rules_version, rules_hash,
                    effective_from, published_at)
                values (gen_random_uuid(), 'OFFICIAL_BORROW_FEE', '1.0.0', 25.500000, 'ACT/365',
                    '1.0.0', ?, ?, ?)
                """).param("e".repeat(64)).param(at(FIRST_FROM)).param(at(FIRST_FROM)).update();
    }

    @Test
    void theVersionInForceIsResolvedAndItsRateIsReadNotDerived() {
        EffectiveTradingPolicy.Fee current = registry.feePolicyAt(SECOND_FROM.plusSeconds(3600));

        assertEquals(FEE_V2, current.policyVersionId());
        assertEquals("2.0.0", current.version());
        // Canonical pins this with a CHECK; the registry reports what is stored.
        assertEquals(20, current.feeRateBps());
    }

    @Test
    void aPastMomentStillResolvesTheVersionThatWasInForceThen() {
        assertEquals(FEE_V1, registry.feePolicyAt(FIRST_FROM.plusSeconds(3600)).policyVersionId());
    }

    @Test
    void theEffectiveFromBoundaryIsInclusiveAndEffectiveToIsExclusive() {
        assertEquals(FEE_V1, registry.feePolicyAt(FIRST_FROM).policyVersionId());
        // The instant the first version ends is already owned by the second.
        assertEquals(FEE_V2, registry.feePolicyAt(SECOND_FROM).policyVersionId());
    }

    @Test
    void aMomentBeforeAnyPublicationFailsInsteadOfFallingBack() {
        MissingTradingPolicyException failure = assertThrows(
                MissingTradingPolicyException.class, () -> registry.feePolicyAt(BEFORE_ANY));
        assertTrue(failure.getMessage().contains("no fee policy version is effective"));
    }

    @Test
    void theOtherThreePolicyKindsResolveTheirPublishedValues() {
        EffectiveTradingPolicy.BuyingPowerBuffer buffer =
                registry.buyingPowerBufferPolicyAt(SECOND_FROM);
        assertEquals(25, buffer.bufferBps(), "the buffer is product data and must be read");

        EffectiveTradingPolicy.ShortRisk shortRisk = registry.shortRiskPolicyAt(SECOND_FROM);
        assertTrue(shortRisk.rulesDocument().contains("maintenanceBps"));

        EffectiveTradingPolicy.ShortBorrowFee borrow = registry.shortBorrowFeePolicyAt(SECOND_FROM);
        assertEquals(0, new BigDecimal("25.500000").compareTo(borrow.annualFeeRateBps()));
        assertEquals("ACT/365", borrow.dayCountBasis());
    }

    @Test
    void everyPolicyKindFailsIndependentlyWhenItHasNoPublishedVersion() {
        for (var call : java.util.List.<Runnable>of(
                () -> registry.buyingPowerBufferPolicyAt(BEFORE_ANY),
                () -> registry.shortRiskPolicyAt(BEFORE_ANY),
                () -> registry.shortBorrowFeePolicyAt(BEFORE_ANY))) {
            assertThrows(MissingTradingPolicyException.class, call::run);
        }
    }

    @Test
    void aResolvedFeePolicyCanBePinnedOntoACanonicalOrder() {
        UUID feePolicyId = registry.feePolicyAt(SECOND_FROM).policyVersionId();

        // The point of the registry: trading.orders.fee_policy_id is a NOT NULL foreign key, so the
        // write path has to resolve a real published version before it can accept an order.
        assertEquals(
                1,
                jdbc.sql("select count(*) from trading.fee_policy_versions where id = ?")
                        .param(feePolicyId).query(Integer.class).single());
    }

    private static void publishFee(UUID id, String version, Instant from, Instant to, String hashSeed) {
        jdbc.sql("""
                insert into trading.fee_policy_versions (id, policy_code, version, fee_rate_bps,
                    calculation_rules_version, rules_hash, effective_from, effective_to, published_at)
                values (?, 'OFFICIAL_SIMULATION_FEE', ?, 20, '1.0.0', ?, ?, ?, ?)
                """)
                .param(id)
                .param(version)
                .param(hashSeed.repeat(64))
                .param(at(from))
                .param(to == null ? null : at(to))
                .param(at(from))
                .update();
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
