package com.idea2strategy.trading.worker.runtime;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Reads the bot's active long lots for position-dependent Basic sell conditions. */
final class PostgresPositionMetricSource implements EvaluatingBotRuntime.PositionMetricSource {

    private static final String ACTIVE_POSITION = """
            select sum(projection.remaining_quantity) as quantity,
                   sum(projection.remaining_cost_basis_amount) as cost_basis,
                   min(lot.opened_at) as opened_at
            from trading.position_lots lot
            join trading.position_lot_projections projection
              on projection.position_lot_id = lot.id
            where lot.bot_id = :botId
              and lot.instrument_id = :instrumentId
              and cast(lot.lot_side as varchar) = 'LONG'
              and projection.remaining_quantity > 0
            """;

    private final JdbcClient jdbc;

    PostgresPositionMetricSource(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<EvaluatingBotRuntime.PositionSnapshot> resolve(UUID botId, UUID instrumentId) {
        return jdbc.sql(ACTIVE_POSITION)
                .param("botId", botId)
                .param("instrumentId", instrumentId)
                .query((resultSet, rowNumber) -> new RawPosition(
                        resultSet.getBigDecimal("quantity"),
                        resultSet.getBigDecimal("cost_basis"),
                        resultSet.getObject("opened_at", OffsetDateTime.class)))
                .optional()
                .filter(position -> position.quantity() != null
                        && position.quantity().signum() > 0
                        && position.costBasis() != null
                        && position.openedAt() != null)
                .map(position -> new EvaluatingBotRuntime.PositionSnapshot(
                        averageEntryPrice(position.costBasis(), position.quantity()),
                        position.openedAt().toInstant()));
    }

    static BigDecimal averageEntryPrice(BigDecimal costBasis, BigDecimal quantity) {
        Objects.requireNonNull(costBasis, "costBasis");
        Objects.requireNonNull(quantity, "quantity");
        if (quantity.signum() <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        return costBasis.divide(quantity, 8, java.math.RoundingMode.HALF_EVEN);
    }

    private record RawPosition(
            BigDecimal quantity, BigDecimal costBasis, OffsetDateTime openedAt) {}
}
