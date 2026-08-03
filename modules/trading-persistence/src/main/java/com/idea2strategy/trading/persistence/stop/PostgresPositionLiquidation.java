package com.idea2strategy.trading.persistence.stop;

import com.idea2strategy.trading.application.port.BotEventStore;
import com.idea2strategy.trading.application.port.OrderIntentBatchStore;
import com.idea2strategy.trading.application.port.PositionLiquidationPort;
import com.idea2strategy.trading.application.stop.StopStepResult;
import com.idea2strategy.trading.domain.eligibility.OrderPositionEffect;
import com.idea2strategy.trading.domain.event.BotEvent;
import com.idea2strategy.trading.domain.event.BotEventAppend;
import com.idea2strategy.trading.domain.event.BotEventType;
import com.idea2strategy.trading.domain.intent.IntentDecision;
import com.idea2strategy.trading.domain.intent.OrderIntent;
import com.idea2strategy.trading.domain.intent.OrderIntentBatch;
import com.idea2strategy.trading.domain.intent.OrderIntentBatchFactory;
import com.idea2strategy.trading.domain.intent.OrderIntentRequest;
import com.idea2strategy.trading.domain.order.OrderSide;
import com.idea2strategy.trading.domain.order.OrderType;
import com.idea2strategy.trading.domain.order.TimeInForce;
import com.idea2strategy.trading.domain.stop.SystemCloseAction;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The LIQUIDATE_POSITIONS step of a bot stop: what the bot still holds becomes canonical
 * system-origin order intents, one batch per partition, and the close actions that record why.
 *
 * <p>No evaluation produced these intents, so every identity is derived from the settlement's own
 * official event — the operation-scoped idempotency key makes a resumed step re-derive the same
 * event, the same batch, the same intents and the same close actions, converging instead of
 * double-selling. The remaining quantities are read from {@code flow_position_projections}, whose
 * own deferred triggers already guarantee they equal the lot movements underneath.
 *
 * <p>Long remainders are closed with a SELL, short remainders covered with a BUY, both as MARKET
 * DAY intents: a stop settlement asks for the position to be gone, not for a price opinion. The
 * fills that execute these intents flow through the ordinary order and fill paths — this step
 * submits the intents and records the actions; it does not pretend the positions are already flat.
 */
@Component
public class PostgresPositionLiquidation implements PositionLiquidationPort {

    private static final String OPEN_POSITIONS = """
            select partition_id, flow_id, instrument_id, long_quantity, short_quantity
            from trading.flow_position_projections
            where bot_id = :bot and (long_quantity > 0 or short_quantity > 0)
            order by partition_id, flow_id, instrument_id
            """;

    private final JdbcClient jdbc;
    private final OrderIntentBatchStore intents;
    private final BotEventStore events;
    private final OrderIntentBatchFactory factory = new OrderIntentBatchFactory();
    private final Clock clock = Clock.systemUTC();

    public PostgresPositionLiquidation(
            JdbcClient jdbc, OrderIntentBatchStore intents, BotEventStore events) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.intents = Objects.requireNonNull(intents, "intents");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    public StopStepResult submitRemainingPositionLiquidations(UUID botId, UUID operationId) {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(operationId, "operationId");
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        List<OpenPosition> positions = jdbc.sql(OPEN_POSITIONS)
                .param("bot", botId)
                .query((rs, row) -> new OpenPosition(
                        rs.getObject("partition_id", UUID.class),
                        rs.getObject("flow_id", UUID.class),
                        rs.getObject("instrument_id", UUID.class),
                        rs.getBigDecimal("long_quantity"),
                        rs.getBigDecimal("short_quantity")))
                .list();
        if (positions.isEmpty()) {
            return StopStepResult.completed("no position to liquidate");
        }

        // One canonical batch per partition: (bot_id, partition_id, source_event_id) is the batch's
        // unique key, and the partition is the isolation boundary everywhere else in this engine.
        Map<UUID, List<OpenPosition>> byPartition = new LinkedHashMap<>();
        positions.forEach(position ->
                byPartition.computeIfAbsent(position.partitionId(), ignored -> new ArrayList<>())
                        .add(position));

        List<SystemCloseAction> actions = new ArrayList<>();
        int intentCount = 0;
        for (Map.Entry<UUID, List<OpenPosition>> partition : byPartition.entrySet()) {
            BotEvent event = events.appendOrLoad(new BotEventAppend(
                    botId,
                    BotEventType.SYSTEM_CLOSE_REQUESTED,
                    "stop:" + operationId + ":liquidation:" + partition.getKey(),
                    operationId,
                    null,
                    now,
                    now,
                    "{\"reason\":\"BOT_STOP\",\"partitionId\":\"" + partition.getKey() + "\"}"));

            List<OrderIntentRequest> requests = new ArrayList<>();
            for (OpenPosition position : partition.getValue()) {
                if (position.longQuantity().signum() > 0) {
                    requests.add(closeRequest(event, position, OrderSide.SELL,
                            OrderPositionEffect.REDUCE_LONG, position.longQuantity()));
                }
                if (position.shortQuantity().signum() > 0) {
                    requests.add(closeRequest(event, position, OrderSide.BUY,
                            OrderPositionEffect.REDUCE_SHORT, position.shortQuantity()));
                }
            }

            OrderIntentBatch stored = intents.createOrLoad(factory.createStopLiquidation(
                    botId, partition.getKey(), event.eventId(), now, requests));
            intentCount += stored.intents().size();
            for (OrderIntent intent : stored.intents()) {
                OrderIntentRequest request = intent.request();
                actions.add(SystemCloseAction.forBotStop(
                        botId,
                        partition.getKey(),
                        request.flowId(),
                        request.instrumentId(),
                        request.requestedQuantity(),
                        intent.intentId(),
                        "{\"reason\":\"BOT_STOP\",\"side\":\"" + request.side()
                                + "\",\"flowId\":\"" + request.flowId() + "\"}",
                        calculationHash(event.eventId(), intent.intentId(),
                                request.requestedQuantity())));
            }
        }

        return StopStepResult.completed(
                "submitted %d liquidation intents across %d partitions"
                        .formatted(intentCount, byPartition.size()),
                actions);
    }

    /**
     * The synthetic candidate a system close stands in for. Derived per flow, instrument and side
     * from the partition's own liquidation event, so a resumed step re-derives the same intent.
     */
    private static OrderIntentRequest closeRequest(
            BotEvent event, OpenPosition position, OrderSide side, OrderPositionEffect effect,
            BigDecimal quantity) {
        UUID candidateId = UUID.nameUUIDFromBytes(
                ("stop-close:" + event.eventId() + ":" + position.flowId() + ":"
                        + position.instrumentId() + ":" + side)
                        .getBytes(StandardCharsets.UTF_8));
        return new OrderIntentRequest(
                candidateId,
                position.flowId(),
                position.instrumentId(),
                side,
                effect,
                OrderType.MARKET,
                TimeInForce.DAY,
                quantity,
                null,
                null,
                null,
                IntentDecision.APPROVED,
                "BOT_STOP",
                quantity);
    }

    /** What was closed, from what, by how much — the auditable derivation of the action. */
    private static String calculationHash(UUID sourceEventId, UUID intentId, BigDecimal quantity) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(("stop-liquidation:v1:" + sourceEventId + ":" + intentId + ":"
                    + quantity.stripTrailingZeros().toPlainString())
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required", unavailable);
        }
    }

    private record OpenPosition(
            UUID partitionId, UUID flowId, UUID instrumentId,
            BigDecimal longQuantity, BigDecimal shortQuantity) {}
}
