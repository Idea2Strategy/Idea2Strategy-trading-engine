package com.idea2strategy.trading.persistence.policy;

import com.idea2strategy.trading.application.policy.MissingTradingPolicyException;
import com.idea2strategy.trading.application.port.TradingPolicyRegistry;
import com.idea2strategy.trading.domain.policy.EffectiveTradingPolicy;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads the published platform policy versions from the canonical {@code trading} schema.
 *
 * <p>Every table shares the same shape: {@code effective_from} inclusive, {@code effective_to}
 * exclusive and nullable for the open-ended current version, guarded by
 * {@code effective_to IS NULL OR effective_to > effective_from}. Ordering by {@code effective_from}
 * descending and taking one row therefore returns the version in force, and returns nothing at all
 * when the moment predates the first publication.
 */
@Repository
public class PostgresTradingPolicyRegistry implements TradingPolicyRegistry {

    private static final String WINDOW = """
             where effective_from <= ?
               and (effective_to is null or effective_to > ?)
             order by effective_from desc
             limit 1
            """;

    private final JdbcClient jdbc;

    public PostgresTradingPolicyRegistry(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public EffectiveTradingPolicy.Fee feePolicyAt(Instant at) {
        return resolve("fee", at, """
                select id, policy_code, version, fee_rate_bps, calculation_rules_version, rules_hash
                  from trading.fee_policy_versions
                """ + WINDOW,
                (rs, row) -> new EffectiveTradingPolicy.Fee(
                        rs.getObject("id", UUID.class),
                        rs.getString("policy_code"),
                        rs.getString("version"),
                        rs.getInt("fee_rate_bps"),
                        rs.getString("calculation_rules_version"),
                        rs.getString("rules_hash")));
    }

    @Override
    public EffectiveTradingPolicy.BuyingPowerBuffer buyingPowerBufferPolicyAt(Instant at) {
        return resolve("buying power buffer", at, """
                select id, policy_code, version, buffer_bps, rounding_rules_version, rules_hash
                  from trading.buying_power_buffer_policy_versions
                """ + WINDOW,
                (rs, row) -> new EffectiveTradingPolicy.BuyingPowerBuffer(
                        rs.getObject("id", UUID.class),
                        rs.getString("policy_code"),
                        rs.getString("version"),
                        rs.getInt("buffer_bps"),
                        rs.getString("rounding_rules_version"),
                        rs.getString("rules_hash")));
    }

    @Override
    public EffectiveTradingPolicy.ShortRisk shortRiskPolicyAt(Instant at) {
        return resolve("short risk", at, """
                select id, policy_code, version, rules_document, rules_hash
                  from trading.short_risk_policy_versions
                """ + WINDOW,
                (rs, row) -> new EffectiveTradingPolicy.ShortRisk(
                        rs.getObject("id", UUID.class),
                        rs.getString("policy_code"),
                        rs.getString("version"),
                        rs.getString("rules_document"),
                        rs.getString("rules_hash")));
    }

    @Override
    public EffectiveTradingPolicy.ShortBorrowFee shortBorrowFeePolicyAt(Instant at) {
        return resolve("short borrow fee", at, """
                select id, policy_code, version, annual_fee_rate_bps, day_count_basis,
                       calculation_rules_version, rules_hash
                  from trading.short_borrow_fee_policy_versions
                """ + WINDOW,
                (rs, row) -> new EffectiveTradingPolicy.ShortBorrowFee(
                        rs.getObject("id", UUID.class),
                        rs.getString("policy_code"),
                        rs.getString("version"),
                        rs.getBigDecimal("annual_fee_rate_bps"),
                        rs.getString("day_count_basis"),
                        rs.getString("calculation_rules_version"),
                        rs.getString("rules_hash")));
    }

    private <T> T resolve(
            String policyKind,
            Instant at,
            String sql,
            org.springframework.jdbc.core.RowMapper<T> mapper) {
        Objects.requireNonNull(at, "at");
        OffsetDateTime moment = at.atOffset(ZoneOffset.UTC);
        Optional<T> resolved = jdbc.sql(sql)
                .param(moment)
                .param(moment)
                .query(mapper)
                .optional();
        return resolved.orElseThrow(() -> new MissingTradingPolicyException(policyKind, at));
    }
}
