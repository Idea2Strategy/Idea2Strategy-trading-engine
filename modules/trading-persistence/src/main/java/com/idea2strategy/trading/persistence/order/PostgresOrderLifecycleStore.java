package com.idea2strategy.trading.persistence.order;

import com.idea2strategy.trading.application.order.CancelOrderCommand;
import com.idea2strategy.trading.application.order.ExpireOrderCommand;
import com.idea2strategy.trading.application.order.FillOrderCommand;
import com.idea2strategy.trading.application.order.OrderLifecycleCommand;
import com.idea2strategy.trading.application.order.OrderLifecycleConflictException;
import com.idea2strategy.trading.application.order.OrderLifecycleVersionConflictException;
import com.idea2strategy.trading.application.port.OrderLifecycleStore;
import com.idea2strategy.trading.domain.order.OrderComponent;
import com.idea2strategy.trading.domain.order.OrderLifecycle;
import com.idea2strategy.trading.domain.order.OrderLifecycleIdentity;
import com.idea2strategy.trading.domain.order.OrderPlacement;
import com.idea2strategy.trading.domain.order.OrderScope;
import com.idea2strategy.trading.domain.order.OrderStatus;
import com.idea2strategy.trading.domain.order.OrderTerms;
import com.idea2strategy.trading.domain.order.OrderType;
import com.idea2strategy.trading.domain.order.OrderSide;
import com.idea2strategy.trading.domain.order.TimeInForce;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes the canonical order tables.
 *
 * <p>Four rows move together and mean different things. {@code trading.orders} is the immutable
 * contract the order was accepted under. {@code order_components} is the attribution back to the
 * intents that composed it. {@code order_events} is the official history, and it is also what makes
 * the write idempotent: {@code bot_event_id} is unique, so a redelivered transition loses the insert
 * rather than applying twice. {@code order_state_projections} is the rebuildable read model, and its
 * {@code last_order_event_sequence} carries the optimistic lock the private schema kept in a
 * {@code version} column.
 */
@Repository
public class PostgresOrderLifecycleStore implements OrderLifecycleStore {
    private static final String IDENTITY_CONFLICT = "Order lifecycle identity conflict";
    private static final int QUANTITY_SCALE = 8;
    private static final int PRICE_SCALE = 8;

    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;

    public PostgresOrderLifecycleStore(JdbcClient jdbcClient, PlatformTransactionManager transactionManager) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    @Override
    public OrderLifecycle createOrLoad(OrderPlacement placement) {
        Objects.requireNonNull(placement, "placement");
        requireInitialCreationAggregate(placement.lifecycle());
        requireExactlyPersistable(placement.lifecycle());
        return transactionTemplate.execute(status -> createOrLoadInTransaction(placement));
    }

    @Override
    public OrderLifecycle apply(OrderLifecycleCommand command) {
        Objects.requireNonNull(command, "command");
        return transactionTemplate.execute(status -> applyInTransaction(command));
    }

    private OrderLifecycle createOrLoadInTransaction(OrderPlacement placement) {
        OrderLifecycle desired = placement.lifecycle();
        OrderScope scope = placement.scope();
        OrderTerms terms = desired.terms();

        int inserted = jdbcClient.sql("""
                        insert into trading.orders (
                            id, bot_id, partition_id, instrument_id, order_key, side, order_type,
                            time_in_force, requested_quantity, limit_price, stop_price,
                            trailing_offset_type, trailing_offset_value, broker_rules_version,
                            precision_rules_version, slippage_rate_bps, fee_policy_id,
                            accepted_event_id, accepted_at, expires_at, contract_hash
                        ) values (
                            :id, :botId, :partitionId, :instrumentId, :orderKey,
                            cast(:side as trading.order_side),
                            cast(:orderType as trading.order_type),
                            cast(:timeInForce as trading.time_in_force),
                            :requestedQuantity, :limitPrice, :stopPrice,
                            cast(:trailingOffsetType as trading.trailing_offset_type),
                            :trailingOffsetValue, :brokerRulesVersion, :precisionRulesVersion,
                            :slippageRateBps, :feePolicyId, :acceptedEventId, :acceptedAt,
                            :expiresAt, :contractHash
                        )
                        on conflict do nothing
                        """)
                .param("id", desired.orderId())
                .param("botId", scope.botId())
                .param("partitionId", scope.partitionId())
                .param("instrumentId", terms.instrumentId())
                .param("orderKey", placement.orderKey())
                .param("side", terms.side().name())
                .param("orderType", terms.type().name())
                .param("timeInForce", terms.timeInForce().name())
                .param("requestedQuantity", terms.quantity())
                .param("limitPrice", terms.limitPrice())
                .param("stopPrice", terms.stopPrice())
                .param("trailingOffsetType", terms.trailPercent() == null ? null : "PERCENT")
                .param("trailingOffsetValue", terms.trailPercent())
                .param("brokerRulesVersion", placement.pins().brokerRulesVersion())
                .param("precisionRulesVersion", placement.pins().precisionRulesVersion())
                .param("slippageRateBps", placement.pins().slippageRateBps())
                .param("feePolicyId", placement.pins().feePolicyId())
                .param("acceptedEventId", placement.acceptedEventId())
                .param("acceptedAt", offset(desired.createdAt()))
                .param("expiresAt", offset(terms.expiresAt()))
                .param("contractHash", placement.contractHash())
                .update();

        if (inserted != 1) {
            OrderLifecycle stored = loadByOrderId(desired.orderId(), false)
                    .orElseThrow(PostgresOrderLifecycleStore::conflict);
            if (!hasSameCreation(stored, desired, placement)) {
                throw conflict();
            }
            return stored;
        }

        for (OrderComponent component : placement.components()) {
            requireOne(insertComponent(placement, component));
        }
        requireOne(insertEvent(scope, desired, null, desired, placement.acceptedEventId()));
        requireOne(insertProjection(scope, desired, placement.acceptedEventId()));
        return desired;
    }

    private OrderLifecycle applyInTransaction(OrderLifecycleCommand command) {
        Optional<StoredEvent> replayed = findEventByBotEvent(command.botEventId());
        if (replayed.isPresent()) {
            return replayOrConflict(replayed.orElseThrow(), command);
        }

        Loaded loaded = loadForUpdate(command.orderId()).orElseThrow(PostgresOrderLifecycleStore::conflict);
        replayed = findEventByBotEvent(command.botEventId());
        if (replayed.isPresent()) {
            return replayOrConflict(replayed.orElseThrow(), command);
        }
        if (loaded.lifecycle().version() != command.expectedVersion()) {
            throw new OrderLifecycleVersionConflictException(
                    "Expected order version %d but found %d"
                            .formatted(command.expectedVersion(), loaded.lifecycle().version()));
        }

        OrderLifecycle next = applyToAggregate(loaded.lifecycle(), command);
        requireExactlyPersistable(next);
        requireOne(insertEvent(loaded.scope(), loaded.lifecycle(), command, next, command.botEventId()));
        int updated = updateProjection(loaded.scope(), next, command.expectedVersion(), command.botEventId());
        if (updated != 1) {
            throw new OrderLifecycleVersionConflictException("Order version changed during lifecycle transition");
        }
        return next;
    }

    /**
     * A redelivered transition is only a replay when it carries the same command. The canonical
     * unique {@code bot_event_id} guarantees at most one event per official cause; this decides
     * whether the caller is repeating that cause or contradicting it.
     */
    private OrderLifecycle replayOrConflict(StoredEvent stored, OrderLifecycleCommand command) {
        if (!stored.orderId().equals(command.orderId())
                || stored.orderSequence() != command.expectedVersion() + 1) {
            throw new OrderLifecycleConflictException("Order lifecycle command identity conflict");
        }
        return loadByOrderId(command.orderId(), false).orElseThrow(PostgresOrderLifecycleStore::conflict);
    }

    private static OrderLifecycle applyToAggregate(OrderLifecycle current, OrderLifecycleCommand command) {
        return switch (command) {
            case FillOrderCommand fill -> current.applyFill(fill.delta(), fill.occurredAt());
            case CancelOrderCommand cancel -> current.cancel(cancel.reason(), cancel.occurredAt());
            case ExpireOrderCommand expire -> current.expire(
                    expire.occurredAt(),
                    current.terms().timeInForce() == TimeInForce.DAY ? expire.daySessionClose() : null);
        };
    }

    private int insertComponent(OrderPlacement placement, OrderComponent component) {
        return jdbcClient.sql("""
                        insert into trading.order_components (
                            id, bot_id, partition_id, order_id, intent_id, component_quantity,
                            component_sequence, composition_rules_version
                        ) values (
                            gen_random_uuid(), :botId, :partitionId, :orderId, :intentId,
                            :componentQuantity, :componentSequence, :compositionRulesVersion
                        )
                        on conflict do nothing
                        """)
                .param("botId", placement.scope().botId())
                .param("partitionId", placement.scope().partitionId())
                .param("orderId", placement.lifecycle().orderId())
                .param("intentId", component.intentId())
                .param("componentQuantity", component.componentQuantity())
                .param("componentSequence", component.componentSequence())
                .param("compositionRulesVersion", placement.pins().compositionRulesVersion())
                .update();
    }

    private int insertEvent(
            OrderScope scope,
            OrderLifecycle previous,
            OrderLifecycleCommand command,
            OrderLifecycle next,
            UUID botEventId) {
        OrderStatus previousStatus = command == null ? null : previous.status();
        return jdbcClient.sql("""
                        insert into trading.order_events (
                            id, bot_id, partition_id, order_id, bot_event_id, order_sequence,
                            event_type, previous_status, new_status, reason_code, occurred_at,
                            event_document
                        ) values (
                            gen_random_uuid(), :botId, :partitionId, :orderId, :botEventId,
                            :orderSequence, :eventType,
                            cast(:previousStatus as trading.order_status),
                            cast(:newStatus as trading.order_status),
                            :reasonCode, :occurredAt, cast(:eventDocument as jsonb)
                        )
                        on conflict do nothing
                        """)
                .param("botId", scope.botId())
                .param("partitionId", scope.partitionId())
                .param("orderId", next.orderId())
                .param("botEventId", botEventId)
                .param("orderSequence", next.version())
                .param("eventType", CanonicalOrderStatus.eventTypeFor(previousStatus, next.status()))
                .param("previousStatus", previousStatus == null ? null : CanonicalOrderStatus.of(previousStatus))
                .param("newStatus", CanonicalOrderStatus.of(next.status()))
                .param("reasonCode", next.terminalReason())
                .param("occurredAt", offset(next.lastTransitionAt()))
                .param("eventDocument", eventDocument(next))
                .update();
    }

    private static String eventDocument(OrderLifecycle next) {
        return "{\"filled_quantity\":\"%s\",\"lifecycle_status\":\"%s\"}"
                .formatted(next.cumulativeFilledQuantity().toPlainString(), next.status().name());
    }

    private int insertProjection(OrderScope scope, OrderLifecycle desired, UUID botEventId) {
        return jdbcClient.sql("""
                        insert into trading.order_state_projections (
                            order_id, bot_id, partition_id, status, filled_quantity,
                            remaining_quantity, reserved_cash, reserved_quantity, active_stop_price,
                            trailing_reference_price, last_order_event_sequence,
                            last_bot_event_sequence, updated_at
                        ) values (
                            :orderId, :botId, :partitionId, cast(:status as trading.order_status),
                            :filledQuantity, :remainingQuantity, 0, 0, :activeStopPrice, null,
                            :lastOrderEventSequence, :lastBotEventSequence, :updatedAt
                        )
                        on conflict do nothing
                        """)
                .param("orderId", desired.orderId())
                .param("botId", scope.botId())
                .param("partitionId", scope.partitionId())
                .param("status", CanonicalOrderStatus.of(desired.status()))
                .param("filledQuantity", scaled(desired.cumulativeFilledQuantity(), QUANTITY_SCALE))
                .param("remainingQuantity", scaled(remaining(desired), QUANTITY_SCALE))
                .param("activeStopPrice", desired.terms().stopPrice())
                .param("lastOrderEventSequence", desired.version())
                .param("lastBotEventSequence", botEventSequence(botEventId))
                .param("updatedAt", offset(desired.lastTransitionAt()))
                .update();
    }

    private int updateProjection(OrderScope scope, OrderLifecycle next, long expectedSequence, UUID botEventId) {
        return jdbcClient.sql("""
                        update trading.order_state_projections
                        set status = cast(:status as trading.order_status),
                            filled_quantity = :filledQuantity,
                            remaining_quantity = :remainingQuantity,
                            active_stop_price = :activeStopPrice,
                            last_order_event_sequence = :lastOrderEventSequence,
                            last_bot_event_sequence = :lastBotEventSequence,
                            updated_at = :updatedAt
                        where order_id = :orderId
                          and bot_id = :botId
                          and partition_id = :partitionId
                          and last_order_event_sequence = :expectedSequence
                        """)
                .param("status", CanonicalOrderStatus.of(next.status()))
                .param("filledQuantity", scaled(next.cumulativeFilledQuantity(), QUANTITY_SCALE))
                .param("remainingQuantity", scaled(remaining(next), QUANTITY_SCALE))
                .param("activeStopPrice", next.status().isTerminal() ? null : next.terms().stopPrice())
                .param("lastOrderEventSequence", next.version())
                .param("lastBotEventSequence", botEventSequence(botEventId))
                .param("updatedAt", offset(next.lastTransitionAt()))
                .param("orderId", next.orderId())
                .param("botId", scope.botId())
                .param("partitionId", scope.partitionId())
                .param("expectedSequence", expectedSequence)
                .update();
    }

    /**
     * Canonical {@code closed_projection_has_no_active_remainder} leaves nothing outstanding on a
     * cancelled or expired order, so the unfilled part is dropped rather than carried.
     */
    private static BigDecimal remaining(OrderLifecycle lifecycle) {
        if (lifecycle.status().isTerminal()) {
            return BigDecimal.ZERO;
        }
        return lifecycle.terms().quantity().subtract(lifecycle.cumulativeFilledQuantity());
    }

    private long botEventSequence(UUID botEventId) {
        return jdbcClient.sql("select event_sequence from bot.bot_events where id = :id")
                .param("id", botEventId)
                .query(Long.class)
                .optional()
                .orElseThrow(PostgresOrderLifecycleStore::conflict);
    }

    private Optional<StoredEvent> findEventByBotEvent(UUID botEventId) {
        return jdbcClient.sql("""
                        select order_id, order_sequence
                        from trading.order_events
                        where bot_event_id = :botEventId
                        """)
                .param("botEventId", botEventId)
                .query((resultSet, rowNumber) -> new StoredEvent(
                        resultSet.getObject("order_id", UUID.class),
                        resultSet.getLong("order_sequence")))
                .optional();
    }

    private Optional<OrderLifecycle> loadByOrderId(UUID orderId, boolean forUpdate) {
        return load(orderId, forUpdate).map(Loaded::lifecycle);
    }

    private Optional<Loaded> loadForUpdate(UUID orderId) {
        return load(orderId, true);
    }

    /**
     * Rebuilds the lifecycle from the canonical rows.
     *
     * <p>Two facts the private schema stored directly are recovered rather than duplicated. The
     * source candidate comes from the intent's canonical {@code intent_key}, and the lifecycle
     * status is read back out of the projection: canonical OPEN means ACCEPTED when nothing is
     * filled and PARTIALLY_FILLED once something is.
     */
    private Optional<Loaded> load(UUID orderId, boolean forUpdate) {
        String lock = forUpdate ? " for update of o" : "";
        return jdbcClient.sql("""
                        select o.id, o.bot_id, o.partition_id, o.instrument_id, o.side,
                               o.order_type, o.time_in_force, o.requested_quantity, o.limit_price,
                               o.stop_price, o.trailing_offset_value, o.accepted_at, o.expires_at,
                               p.status, p.filled_quantity, p.last_order_event_sequence,
                               p.updated_at,
                               c.intent_id, i.intent_key,
                               (select e.reason_code from trading.order_events e
                                 where e.order_id = o.id
                                 order by e.order_sequence desc limit 1) as reason_code
                        from trading.orders o
                        join trading.order_state_projections p on p.order_id = o.id
                        join trading.order_components c on c.order_id = o.id
                        join trading.order_intents i on i.id = c.intent_id
                        where o.id = :orderId
                        """ + lock)
                .param("orderId", orderId)
                .query((resultSet, rowNumber) -> toLoaded(resultSet))
                .optional();
    }

    private static Loaded toLoaded(ResultSet resultSet) throws SQLException {
        OrderTerms terms = new OrderTerms(
                resultSet.getObject("intent_id", UUID.class),
                candidateOf(resultSet.getString("intent_key")),
                resultSet.getObject("instrument_id", UUID.class),
                OrderSide.valueOf(resultSet.getString("side")),
                resultSet.getBigDecimal("requested_quantity"),
                OrderType.valueOf(resultSet.getString("order_type")),
                TimeInForce.valueOf(resultSet.getString("time_in_force")),
                resultSet.getBigDecimal("limit_price"),
                resultSet.getBigDecimal("stop_price"),
                resultSet.getBigDecimal("trailing_offset_value"),
                instant(resultSet.getObject("expires_at", OffsetDateTime.class)));

        BigDecimal filled = resultSet.getBigDecimal("filled_quantity").stripTrailingZeros();
        OrderStatus status = lifecycleStatus(resultSet.getString("status"), filled);
        Instant createdAt = instant(resultSet.getObject("accepted_at", OffsetDateTime.class));
        String reasonCode = resultSet.getString("reason_code");
        UUID orderId = resultSet.getObject("id", UUID.class);

        OrderLifecycle lifecycle = new OrderLifecycle(
                orderId,
                OrderLifecycleIdentity.createCommandId(orderId),
                OrderLifecycleIdentity.requestFingerprint(
                        terms,
                        status == OrderStatus.REJECTED ? OrderStatus.REJECTED : OrderStatus.ACCEPTED,
                        createdAt,
                        status == OrderStatus.REJECTED ? reasonCode : null),
                terms,
                status,
                filled,
                resultSet.getLong("last_order_event_sequence"),
                createdAt,
                instant(resultSet.getObject("updated_at", OffsetDateTime.class)),
                status.isTerminal() ? reasonCode : null);
        return new Loaded(
                lifecycle,
                new OrderScope(
                        resultSet.getObject("bot_id", UUID.class),
                        resultSet.getObject("partition_id", UUID.class)));
    }

    private static OrderStatus lifecycleStatus(String canonical, BigDecimal filled) {
        return switch (canonical) {
            case "OPEN" -> filled.signum() > 0 ? OrderStatus.PARTIALLY_FILLED : OrderStatus.ACCEPTED;
            case "PENDING" -> OrderStatus.ACCEPTED;
            case "FILLED" -> OrderStatus.FILLED;
            case "CANCELLED" -> OrderStatus.CANCELLED;
            case "EXPIRED" -> OrderStatus.EXPIRED;
            case "REJECTED" -> OrderStatus.REJECTED;
            default -> throw new IllegalArgumentException("unknown canonical order status " + canonical);
        };
    }

    private static UUID candidateOf(String intentKey) {
        return UUID.fromString(intentKey.substring("candidate:".length()));
    }

    private boolean hasSameCreation(OrderLifecycle stored, OrderLifecycle desired, OrderPlacement placement) {
        String storedContractHash = jdbcClient.sql("select contract_hash from trading.orders where id = :id")
                .param("id", desired.orderId())
                .query(String.class)
                .single();
        return stored.orderId().equals(desired.orderId())
                && stored.terms().equals(desired.terms())
                && stored.createdAt().equals(desired.createdAt())
                && placement.contractHash().equals(storedContractHash);
    }

    private static void requireExactlyPersistable(OrderLifecycle lifecycle) {
        requireCanonicalDecimal(lifecycle.terms().quantity(), QUANTITY_SCALE, "quantity");
        requireCanonicalDecimal(lifecycle.terms().limitPrice(), PRICE_SCALE, "limitPrice");
        requireCanonicalDecimal(lifecycle.terms().stopPrice(), PRICE_SCALE, "stopPrice");
        requireCanonicalDecimal(lifecycle.terms().trailPercent(), PRICE_SCALE, "trailPercent");
        requireCanonicalDecimal(
                lifecycle.cumulativeFilledQuantity(), QUANTITY_SCALE, "cumulativeFilledQuantity");
        requireDatabaseInstant(lifecycle.terms().expiresAt(), "expiresAt");
        requireDatabaseInstant(lifecycle.createdAt(), "createdAt");
        requireDatabaseInstant(lifecycle.lastTransitionAt(), "lastTransitionAt");
    }

    private static void requireInitialCreationAggregate(OrderLifecycle desired) {
        if (desired.version() != 1
                || (desired.status() != OrderStatus.ACCEPTED && desired.status() != OrderStatus.REJECTED)) {
            throw new IllegalArgumentException("createOrLoad requires an initial ACCEPTED or REJECTED aggregate");
        }
    }

    private static BigDecimal scaled(BigDecimal value, int scale) {
        return value == null ? null : value.setScale(scale, RoundingMode.UNNECESSARY);
    }

    private static void requireCanonicalDecimal(BigDecimal value, int scale, String name) {
        if (value == null) {
            return;
        }
        try {
            value.setScale(scale, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException notExactlyRepresentable) {
            throw new IllegalArgumentException(
                    name + " exceeds the canonical scale of " + scale, notExactlyRepresentable);
        }
    }

    private static void requireDatabaseInstant(Instant value, String name) {
        if (value != null && value.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException(name + " must have microsecond precision for PostgreSQL");
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

    private static OrderLifecycleConflictException conflict() {
        return new OrderLifecycleConflictException(IDENTITY_CONFLICT);
    }

    private record StoredEvent(UUID orderId, long orderSequence) {
    }

    private record Loaded(OrderLifecycle lifecycle, OrderScope scope) {
    }
}
