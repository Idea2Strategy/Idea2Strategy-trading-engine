package com.idea2strategy.trading.worker.market;

import com.idea2strategy.trading.market.redis.MarketEventStreamEntry;
import com.idea2strategy.trading.messaging.market.MarketEventEnvelope;
import com.idea2strategy.trading.messaging.market.MarketEventType;
import com.idea2strategy.trading.worker.runtime.EvaluatingBotRuntime;
import io.lettuce.core.Consumer;
import io.lettuce.core.RedisBusyException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XAutoClaimArgs;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RT3: the gateway's published market events reach the evaluation loop.
 *
 * <p>C07 put normalised events on a Redis stream and RT2 built the loop that can decide on one, with
 * nothing in between. Until this consumer existed a deployed worker evaluated only what a test handed
 * it, so a running bot in production saw no market at all.
 *
 * <p>Delivery is a consumer group, which is what makes replicas cooperate rather than duplicate: the
 * group name identifies the consumer — every trading worker shares it — while the consumer name
 * identifies the replica, so an entry goes to exactly one of them and an entry a dead replica never
 * acknowledged can be found again. Each cycle first reclaims entries whose pending time has passed the
 * lease, then reads new ones.
 *
 * <p><strong>Acknowledged after feeding, never before.</strong> A worker that died between the two
 * redelivers the entry, and redelivery is safe by construction rather than by hope: the runtime derives
 * its batch id from the event, so the candidate processor's claim ledger recognises the second attempt,
 * and a bot's own sequence guard drops any event that does not move it forward. An entry whose decode
 * or evaluation throws is retried a bounded number of times and then isolated in a dead-letter
 * stream, so one poison entry cannot remain pending forever.
 *
 * <p><strong>What this consumer does not re-do.</strong> Ordering, duplicate suppression and correction
 * handling (C06) run on the producer, before publication; the stream may still carry an out-of-order or
 * corrected entry, and the runtime's per-bot sequence guard is what decides on those. Re-running C06
 * here would need the gateway's per-stream state, which this process does not have and must not guess.
 *
 * <p><strong>C09.</strong> The consumer owns its group lag and reads the gateway's instrument-keyed
 * availability projection. Missing, stale or malformed authority is a denial. A denied entry is
 * acknowledged without evaluation and counted separately; replaying it after a later recovery could
 * create a stale order. The remaining gateway-owned inputs
 * — provider connectivity and session state — are the gateway's knowledge and are keyed by symbol,
 * and remain there deliberately, so this process never invents an instrument-to-symbol mapping.
 */
public final class RedisMarketEventStreamConsumer {

    private static final Logger log = LoggerFactory.getLogger(RedisMarketEventStreamConsumer.class);

    /** The group every trading worker shares, so one entry reaches one replica. */
    public static final String CONSUMER_GROUP = "trading-workers";

    private static final String RECORD_FAILURE_SCRIPT = """
            local attempts = redis.call('HINCRBY', KEYS[2], ARGV[1], 1)
            if attempts < tonumber(ARGV[2]) then
              return attempts
            end
            redis.call('XADD', KEYS[3], '*',
              'sourceStream', KEYS[1],
              'sourceEntryId', ARGV[1],
              'consumerGroup', ARGV[3],
              'failure', ARGV[4],
              'body', ARGV[5])
            redis.call('XTRIM', KEYS[3], 'MAXLEN', '=', tonumber(ARGV[6]))
            redis.call('XACK', KEYS[1], ARGV[3], ARGV[1])
            redis.call('HDEL', KEYS[2], ARGV[1])
            return attempts
            """;

    private final RedisCommands<String, String> commands;
    private final EvaluatingBotRuntime runtime;
    private final String streamKey;
    private final String consumerName;
    private final int batchSize;
    private final Duration reclaimAfter;
    private final long maximumEntryLag;
    private final MarketEventAvailabilityPolicy availabilityPolicy;
    private final int maximumDeliveryAttempts;
    private final int deadLetterCapacity;
    private final AtomicLong lastObservedLag = new AtomicLong();
    private final AtomicLong processedEntries = new AtomicLong();
    private final AtomicLong catchUpEntries = new AtomicLong();
    private final AtomicLong deadLetteredEntries = new AtomicLong();
    private final AtomicLong availabilitySkippedEntries = new AtomicLong();

    private boolean groupReady;

    public RedisMarketEventStreamConsumer(
            RedisCommands<String, String> commands,
            EvaluatingBotRuntime runtime,
            String streamKey,
            String consumerName,
            int batchSize,
            Duration reclaimAfter,
            long maximumEntryLag) {
        this(commands, runtime, streamKey, consumerName, batchSize, reclaimAfter, maximumEntryLag,
                event -> true, 5, 10_000);
    }

    public RedisMarketEventStreamConsumer(
            RedisCommands<String, String> commands,
            EvaluatingBotRuntime runtime,
            String streamKey,
            String consumerName,
            int batchSize,
            Duration reclaimAfter,
            long maximumEntryLag,
            MarketEventAvailabilityPolicy availabilityPolicy) {
        this(commands, runtime, streamKey, consumerName, batchSize, reclaimAfter, maximumEntryLag,
                availabilityPolicy, 5, 10_000);
    }

    public RedisMarketEventStreamConsumer(
            RedisCommands<String, String> commands,
            EvaluatingBotRuntime runtime,
            String streamKey,
            String consumerName,
            int batchSize,
            Duration reclaimAfter,
            long maximumEntryLag,
            MarketEventAvailabilityPolicy availabilityPolicy,
            int maximumDeliveryAttempts,
            int deadLetterCapacity) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.streamKey = requireText(streamKey, "streamKey");
        this.consumerName = requireText(consumerName, "consumerName");
        this.batchSize = requirePositive(batchSize, "batchSize");
        this.reclaimAfter = requirePositiveDuration(reclaimAfter, "reclaimAfter");
        if (maximumEntryLag < 0) {
            throw new IllegalArgumentException("maximumEntryLag must not be negative");
        }
        this.maximumEntryLag = maximumEntryLag;
        this.availabilityPolicy = Objects.requireNonNull(availabilityPolicy, "availabilityPolicy");
        this.maximumDeliveryAttempts = requirePositive(maximumDeliveryAttempts, "maximumDeliveryAttempts");
        this.deadLetterCapacity = requirePositive(deadLetterCapacity, "deadLetterCapacity");
    }

    /**
     * One polling cycle: reclaim what a dead replica left pending, read what is new, feed and
     * acknowledge each. Returns the number of entries fed.
     */
    public int pollOnce() {
        ensureGroup();
        long lag = entryLag();
        lastObservedLag.set(lag);
        boolean catchUp = lag > maximumEntryLag;
        if (catchUp) {
            log.warn("market event consumption is {} entries behind, beyond the {} allowed; "
                    + "draining stale entries without producing orders", lag, maximumEntryLag);
        }
        int processed = deliver(reclaimAbandoned(), catchUp);
        return processed + deliver(readNew(), catchUp);
    }

    /**
     * Creates the group at the stream's start, once.
     *
     * <p>From {@code 0-0} rather than {@code $}, so a group created after the gateway began publishing
     * reads the events already there instead of silently starting from the next one. A group that
     * already exists is not an error — it is the normal case for every replica after the first.
     */
    private void ensureGroup() {
        if (groupReady) {
            return;
        }
        try {
            commands.xgroupCreate(
                    XReadArgs.StreamOffset.from(streamKey, "0-0"),
                    CONSUMER_GROUP,
                    XGroupCreateArgs.Builder.mkstream());
        } catch (RedisBusyException alreadyExists) {
            log.debug("market event consumer group {} already exists", CONSUMER_GROUP);
        }
        groupReady = true;
    }

    private List<StreamMessage<String, String>> readNew() {
        List<StreamMessage<String, String>> messages = commands.xreadgroup(
                Consumer.from(CONSUMER_GROUP, consumerName),
                XReadArgs.Builder.count(batchSize),
                XReadArgs.StreamOffset.lastConsumed(streamKey));
        return messages == null ? List.of() : messages;
    }

    /** Entries another consumer took and never acknowledged, once their lease has passed. */
    private List<StreamMessage<String, String>> reclaimAbandoned() {
        var reclaimed = commands.xautoclaim(
                streamKey,
                XAutoClaimArgs.Builder.xautoclaim(
                                Consumer.from(CONSUMER_GROUP, consumerName), reclaimAfter, "0-0")
                        .count(batchSize));
        return reclaimed == null || reclaimed.getMessages() == null
                ? List.of()
                : reclaimed.getMessages();
    }

    private int deliver(List<StreamMessage<String, String>> messages, boolean catchUp) {
        int processed = 0;
        for (StreamMessage<String, String> message : messages) {
            if (message.getBody() == null || message.getBody().isEmpty()) {
                // A tombstone left by a trimmed or deleted entry: acknowledge it, there is nothing to
                // deliver and leaving it pending would have every cycle reclaim it forever.
                commands.xack(streamKey, CONSUMER_GROUP, message.getId());
                continue;
            }
            try {
                MarketEventEnvelope event = MarketEventStreamEntry.decode(message.getBody());
                if (event.eventType() != MarketEventType.MARKET_EVALUATION_READY) {
                    acknowledge(message.getId());
                    log.warn("non-evaluation event {} appeared on the evaluation stream and was ignored",
                            event.eventId());
                    continue;
                }
                if (!catchUp && !availabilityPolicy.permits(event)) {
                    acknowledge(message.getId());
                    availabilitySkippedEntries.incrementAndGet();
                    log.warn("market event {} is blocked by the gateway availability projection; "
                                    + "acknowledged without evaluation",
                            event.eventId());
                    continue;
                }
                if (catchUp) {
                    runtime.catchUp(event);
                    catchUpEntries.incrementAndGet();
                } else {
                    runtime.feed(event);
                }
                acknowledge(message.getId());
                processed++;
                processedEntries.incrementAndGet();
            } catch (RuntimeException failure) {
                long attempts = recordFailure(message, failure);
                if (attempts >= maximumDeliveryAttempts) {
                    deadLetteredEntries.incrementAndGet();
                    log.error("market event entry {} failed {} times and moved to {}",
                            message.getId(), attempts, deadLetterStreamKey(), failure);
                } else {
                    log.error("market event entry {} could not be evaluated on attempt {}; left pending",
                            message.getId(), attempts, failure);
                }
            }
        }
        return processed;
    }

    private void acknowledge(String messageId) {
        commands.xack(streamKey, CONSUMER_GROUP, messageId);
        commands.hdel(failureCountKey(), messageId);
    }

    private long recordFailure(StreamMessage<String, String> message, RuntimeException failure) {
        Object result = commands.eval(
                RECORD_FAILURE_SCRIPT,
                ScriptOutputType.INTEGER,
                new String[] {streamKey, failureCountKey(), deadLetterStreamKey()},
                message.getId(),
                Integer.toString(maximumDeliveryAttempts),
                CONSUMER_GROUP,
                failure.getClass().getName() + ": " + Objects.toString(failure.getMessage(), ""),
                Objects.toString(message.getBody(), "{}"),
                Integer.toString(deadLetterCapacity));
        return result instanceof Number number ? number.longValue() : Long.parseLong(result.toString());
    }

    String failureCountKey() {
        return streamKey + ":delivery-failures";
    }

    String deadLetterStreamKey() {
        return streamKey + ":dead-letter";
    }

    long lastObservedLag() {
        return lastObservedLag.get();
    }

    long processedEntries() {
        return processedEntries.get();
    }

    long catchUpEntries() {
        return catchUpEntries.get();
    }

    long deadLetteredEntries() {
        return deadLetteredEntries.get();
    }

    long availabilitySkippedEntries() {
        return availabilitySkippedEntries.get();
    }

    /**
     * How far this group is behind the stream.
     *
     * <p>Read from {@code XINFO GROUPS}, which is where Redis already keeps it. A lag Redis cannot
     * compute — which happens after a trim or a manual {@code SETID} — reads as zero rather than as an
     * outage, because refusing to evaluate over an unknown number would stop trading on a maintenance
     * operation.
     */
    @SuppressWarnings("unchecked")
    private long entryLag() {
        for (Object group : commands.xinfoGroups(streamKey)) {
            List<Object> fields = (List<Object>) group;
            String name = null;
            Long lag = null;
            for (int index = 0; index + 1 < fields.size(); index += 2) {
                Object key = fields.get(index);
                Object value = fields.get(index + 1);
                if ("name".equals(key)) {
                    name = String.valueOf(value);
                } else if ("lag".equals(key) && value instanceof Number number) {
                    lag = number.longValue();
                }
            }
            if (CONSUMER_GROUP.equals(name)) {
                return lag == null ? 0L : lag;
            }
        }
        return 0L;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static Duration requirePositiveDuration(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
