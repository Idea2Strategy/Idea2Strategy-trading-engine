package com.idea2strategy.trading.persistence.stop;

import com.idea2strategy.trading.application.order.CancelOrderCommand;
import com.idea2strategy.trading.application.port.BotEventStore;
import com.idea2strategy.trading.application.port.OpenOrderCleanupPort;
import com.idea2strategy.trading.application.port.OrderLifecycleStore;
import com.idea2strategy.trading.application.port.ResourceReservationStore;
import com.idea2strategy.trading.application.reservation.ReleaseReservationCommand;
import com.idea2strategy.trading.application.stop.StopStepResult;
import com.idea2strategy.trading.domain.event.BotEvent;
import com.idea2strategy.trading.domain.event.BotEventAppend;
import com.idea2strategy.trading.domain.event.BotEventType;
import com.idea2strategy.trading.domain.reservation.ReservationReleaseCause;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The CANCEL_ORDERS_AND_RELEASE step of a bot stop, done against the canonical tables.
 *
 * <p>Everything here goes through the same write paths a live trading day uses — a cancellation is
 * an official order event, a release is an official reservation event, and each one is backed by
 * its own {@code bot.bot_events} row. The stop is not a different kind of bookkeeping; it is the
 * ordinary bookkeeping, driven to completion.
 *
 * <p>Idempotency is by state, not by memory. The step selects what is still open and what is still
 * active; whatever an earlier attempt already closed simply no longer matches, so a settlement
 * resumed after a crash finishes the remainder instead of failing on work already done. The bot
 * events use operation-scoped idempotency keys, so a retried transition converges on the row its
 * first attempt appended.
 */
@Component
public class PostgresOpenOrderCleanup implements OpenOrderCleanupPort {

    /** Order states the projection can still move to CANCELLED. */
    private static final String OPEN_ORDERS = """
            select order_id, last_order_event_sequence
            from trading.order_state_projections
            where bot_id = :bot and cast(status as varchar) in ('PENDING', 'OPEN')
            order by order_id
            """;

    private static final String ACTIVE_RESERVATIONS = """
            select id, last_event_sequence
            from trading.resource_reservations
            where bot_id = :bot and cast(status as varchar) = 'ACTIVE'
            order by id
            """;

    private final JdbcClient jdbc;
    private final OrderLifecycleStore orders;
    private final ResourceReservationStore reservations;
    private final BotEventStore events;
    /** Wall time; the assertions that matter here are states, and states carry their own moments. */
    private final Clock clock = Clock.systemUTC();

    public PostgresOpenOrderCleanup(
            JdbcClient jdbc,
            OrderLifecycleStore orders,
            ResourceReservationStore reservations,
            BotEventStore events) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.orders = Objects.requireNonNull(orders, "orders");
        this.reservations = Objects.requireNonNull(reservations, "reservations");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    public StopStepResult cancelOpenOrdersAndReleaseReservations(UUID botId, UUID operationId) {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(operationId, "operationId");
        // PostgreSQL keeps microseconds and the canonical writes verify exactly that precision.
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);

        List<Target> openOrders = jdbc.sql(OPEN_ORDERS)
                .param("bot", botId)
                .query((rs, row) -> new Target(
                        rs.getObject("order_id", UUID.class),
                        rs.getLong("last_order_event_sequence")))
                .list();
        for (Target order : openOrders) {
            BotEvent event = events.appendOrLoad(new BotEventAppend(
                    botId,
                    BotEventType.ORDER_CANCELLED,
                    key(operationId, "cancel-order", order.id()),
                    operationId,
                    null,
                    now,
                    now,
                    "{\"reason\":\"BOT_STOP\",\"orderId\":\"" + order.id() + "\"}"));
            orders.apply(new CancelOrderCommand(
                    commandId(operationId, order.id()),
                    order.id(),
                    event.eventId(),
                    order.sequence(),
                    "BOT_STOP",
                    now));
        }

        List<Target> activeReservations = jdbc.sql(ACTIVE_RESERVATIONS)
                .param("bot", botId)
                .query((rs, row) -> new Target(
                        rs.getObject("id", UUID.class),
                        rs.getLong("last_event_sequence")))
                .list();
        for (Target reservation : activeReservations) {
            BotEvent event = events.appendOrLoad(new BotEventAppend(
                    botId,
                    BotEventType.RESERVATION_SETTLED,
                    key(operationId, "release-reservation", reservation.id()),
                    operationId,
                    null,
                    now,
                    now,
                    "{\"reason\":\"BOT_STOP\",\"reservationId\":\"" + reservation.id() + "\"}"));
            reservations.apply(new ReleaseReservationCommand(
                    reservation.id(),
                    reservation.sequence(),
                    event.eventId(),
                    ReservationReleaseCause.CANCEL,
                    now));
        }

        return StopStepResult.completed(
                "cancelled %d open orders, released %d active reservations"
                        .formatted(openOrders.size(), activeReservations.size()));
    }

    /** Deterministic per operation and per target, so a retried step converges on its own rows. */
    private static String key(UUID operationId, String action, UUID targetId) {
        return "stop:" + operationId + ":" + action + ":" + targetId;
    }

    private static UUID commandId(UUID operationId, UUID orderId) {
        return UUID.nameUUIDFromBytes(
                ("stop-cancel:" + operationId + ":" + orderId)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private record Target(UUID id, long sequence) {}
}
