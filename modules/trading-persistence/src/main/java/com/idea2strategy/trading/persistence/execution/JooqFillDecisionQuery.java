package com.idea2strategy.trading.persistence.execution;

import com.idea2strategy.trading.domain.execution.FillDecision;
import com.idea2strategy.trading.domain.execution.FillEligibility;
import com.idea2strategy.trading.domain.execution.RecordedMarketSnapshot;
import com.idea2strategy.trading.domain.execution.VirtualFill;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

@Repository
public class JooqFillDecisionQuery {
    private final JdbcClient jdbc;

    public JooqFillDecisionQuery(DataSource dataSource) { this(JdbcClient.create(dataSource)); }
    @Autowired
    public JooqFillDecisionQuery(JdbcClient jdbc) { this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc"); }

    public Optional<FillDecision> findById(UUID decisionId) {
        return jdbc.sql("""
                select decision_id, request_fingerprint, order_id, order_version, snapshot_id,
                       instrument_id, observed_at, bid_price, bid_size, ask_price, ask_size,
                       last_trade_price, last_trade_size, trailing_reference_price, eligibility,
                       fill_id, fill_quantity, reference_price, fill_price, notional,
                       slippage_amount, fee, partial, evaluated_at
                from trading.virtual_fill_decision where decision_id = :decisionId
                """).param("decisionId", decisionId).query(JooqFillDecisionQuery::map).optional();
    }

    private static FillDecision map(ResultSet rs, int row) throws SQLException {
        RecordedMarketSnapshot snapshot = new RecordedMarketSnapshot(
                rs.getObject("snapshot_id", UUID.class), rs.getObject("instrument_id", UUID.class),
                rs.getObject("observed_at", java.time.OffsetDateTime.class).toInstant(),
                rs.getBigDecimal("bid_price"), rs.getBigDecimal("bid_size"),
                rs.getBigDecimal("ask_price"), rs.getBigDecimal("ask_size"),
                rs.getBigDecimal("last_trade_price"), rs.getBigDecimal("last_trade_size"),
                rs.getBigDecimal("trailing_reference_price"));
        UUID fillId = rs.getObject("fill_id", UUID.class);
        VirtualFill fill = fillId == null ? null : new VirtualFill(fillId, rs.getBigDecimal("fill_quantity"),
                rs.getBigDecimal("reference_price"), rs.getBigDecimal("fill_price"), rs.getBigDecimal("notional"),
                rs.getBigDecimal("slippage_amount"), rs.getBigDecimal("fee"), rs.getBoolean("partial"),
                rs.getObject("evaluated_at", java.time.OffsetDateTime.class).toInstant());
        return new FillDecision(rs.getObject("decision_id", UUID.class), rs.getString("request_fingerprint"),
                rs.getObject("order_id", UUID.class), rs.getLong("order_version"), snapshot,
                FillEligibility.valueOf(rs.getString("eligibility")), fill,
                rs.getObject("evaluated_at", java.time.OffsetDateTime.class).toInstant());
    }
}
