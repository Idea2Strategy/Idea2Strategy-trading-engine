package com.idea2strategy.trading.worker.control;

import com.idea2strategy.trading.strategy.runtime.control.BotControlCheckpoint;
import com.idea2strategy.trading.strategy.runtime.control.BotControlCheckpointStore;
import com.idea2strategy.trading.strategy.runtime.control.BotControlStatus;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The control checkpoint as a projection of {@code operations.outbox_consumer_receipts} (RT5).
 *
 * <p>The consumer needs three facts about a bot before it acts on a command: which commands it has
 * already completed, how far the aggregate sequence has advanced, and whether the bot is running or
 * stopped. All three are already recorded — a receipt per (handler, message) is what
 * {@link StrategyBotOutboxPoller} writes to make its at-least-once delivery idempotent — so this
 * store derives them instead of keeping a second copy that could disagree.
 *
 * <p>{@code save} therefore writes nothing. The receipt the poller commits after a successful
 * consume <em>is</em> the checkpoint's persistence, and deriving the state from it means a redelivery
 * cannot find a checkpoint that advanced without its receipt, or a receipt whose checkpoint was lost.
 * The consumer's in-memory contract is unchanged: it still calls {@code save}, and the next
 * {@code find} still reflects the transition.
 */
public class OutboxReceiptBotControlCheckpointStore implements BotControlCheckpointStore {

    private static final String PROJECTION = """
            select message.event_type,
                   message.aggregate_sequence,
                   receipt.producer_idempotency_key
            from operations.outbox_consumer_receipts receipt
            join operations.outbox_messages message on message.id = receipt.outbox_message_id
            where receipt.consumer_handler_id = :handlerId
              and receipt.status = 'COMPLETED'
              and message.owner_domain = 'strategy-bot'
              and message.aggregate_id = :botId
            """;

    private final JdbcClient jdbc;
    private final String handlerId;

    public OutboxReceiptBotControlCheckpointStore(JdbcClient jdbc, String handlerId) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.handlerId = Objects.requireNonNull(handlerId, "handlerId");
    }

    @Override
    public Optional<BotControlCheckpoint> find(UUID botId) {
        Objects.requireNonNull(botId, "botId");
        var rows = jdbc.sql(PROJECTION)
                .param("handlerId", handlerId)
                .param("botId", botId)
                .query((rs, row) -> new CompletedCommand(
                        rs.getString("event_type"),
                        rs.getLong("aggregate_sequence"),
                        rs.getString("producer_idempotency_key")))
                .list();
        if (rows.isEmpty()) {
            return Optional.empty();
        }

        Set<String> processed = new LinkedHashSet<>();
        long lastSequence = 0;
        boolean stopped = false;
        for (CompletedCommand row : rows) {
            processed.add(row.producerIdempotencyKey());
            lastSequence = Math.max(lastSequence, row.aggregateSequence());
            stopped |= "BOT_STOP_COMMAND".equals(row.eventType());
        }

        // A stop is absorbing in the consumer: once it has stopped a bot, every later run command is
        // ignored. So the status is not "whatever the newest command was" — a run completed as
        // OUT_OF_ORDER_IGNORED would read as RUNNING — it is STOPPED as soon as any stop completed.
        return Optional.of(new BotControlCheckpoint(
                botId,
                // Write-only on the consumer's side: it records the hash it verified but never reads
                // one back, and the verification itself is against the compiled plan, not this.
                Optional.empty(),
                stopped ? BotControlStatus.STOPPED : BotControlStatus.RUNNING,
                lastSequence,
                processed));
    }

    /** The poller's receipt is the persistence; see the class note. */
    @Override
    public void save(BotControlCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
    }

    private record CompletedCommand(
            String eventType, long aggregateSequence, String producerIdempotencyKey) {}
}
