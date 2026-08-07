package com.idea2strategy.trading.market.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.trading.market.alpaca.MarketEventHandlingResult;
import com.idea2strategy.trading.market.availability.MarketDataAvailabilityProjection;
import com.idea2strategy.trading.market.availability.MarketDataAvailabilityResult;
import com.idea2strategy.trading.messaging.market.MarketEventEnvelope;
import com.idea2strategy.trading.messaging.market.MarketEventType;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class RedisMarketEventPublisher implements AutoCloseable {
    private static final TypeReference<Map<String, BigDecimal>> VALUES_TYPE = new TypeReference<>() {};

    private static final String PUBLISH_SCRIPT = """
            local function assert_type(key, expected)
              local actual = redis.call('TYPE', key).ok
              if actual ~= 'none' and actual ~= expected then
                return redis.error_reply('WRONGTYPE key ' .. key .. ' must be ' .. expected)
              end
            end

            local type_error = assert_type(KEYS[1], 'stream')
            if type_error ~= nil then
              return type_error
            end
            type_error = assert_type(KEYS[2], 'hash')
            if type_error ~= nil then
              return type_error
            end
            type_error = assert_type(KEYS[3], 'zset')
            if type_error ~= nil then
              return type_error
            end
            if string.sub(ARGV[6], 1, 4) == 'BAR_' then
              type_error = assert_type(KEYS[4], 'zset')
              if type_error ~= nil then
                return type_error
              end
            end

            redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', ARGV[18])
            if redis.call('ZADD', KEYS[3], 'NX', ARGV[17], ARGV[1]) == 0 then
              return {0, '', 0}
            end

            local stream_id = redis.call(
              'XADD', KEYS[1], '*',
              'eventId', ARGV[1],
              'schemaVersion', ARGV[2],
              'instrumentId', ARGV[3],
              'provider', ARGV[4],
              'feed', ARGV[5],
              'eventType', ARGV[6],
              'providerEventId', ARGV[7],
              'occurredAt', ARGV[8],
              'receivedAt', ARGV[9],
              'sequence', ARGV[10],
              'revision', ARGV[11],
              'correctionOfEventId', ARGV[12],
              'values', ARGV[13])
            redis.call('XTRIM', KEYS[1], 'MAXLEN', '=', tonumber(ARGV[19]))

            local latest_updated = 0
            if ARGV[14] == '1' then
              local stored_sequence = redis.call('HGET', KEYS[2], 'sequence')
              local stored_revision = redis.call('HGET', KEYS[2], 'revision')
              if stored_sequence == false
                  or tonumber(ARGV[10]) > tonumber(stored_sequence)
                  or (tonumber(ARGV[10]) == tonumber(stored_sequence)
                      and tonumber(ARGV[11]) > tonumber(stored_revision)) then
                redis.call(
                  'HSET', KEYS[2],
                  'eventId', ARGV[1],
                  'schemaVersion', ARGV[2],
                  'instrumentId', ARGV[3],
                  'provider', ARGV[4],
                  'feed', ARGV[5],
                  'eventType', ARGV[6],
                  'providerEventId', ARGV[7],
                  'occurredAt', ARGV[8],
                  'receivedAt', ARGV[9],
                  'sequence', ARGV[10],
                  'revision', ARGV[11],
                  'correctionOfEventId', ARGV[12],
                  'values', ARGV[13],
                  'streamEntryId', stream_id)
                latest_updated = 1
              end
            end

            if string.sub(ARGV[6], 1, 4) == 'BAR_' then
              redis.call('ZREMRANGEBYSCORE', KEYS[4], ARGV[10], ARGV[10])
              redis.call('ZADD', KEYS[4], ARGV[10], ARGV[16])
              local bar_count = redis.call('ZCARD', KEYS[4])
              local capacity = tonumber(ARGV[15])
              if bar_count > capacity then
                redis.call('ZREMRANGEBYRANK', KEYS[4], 0, bar_count - capacity - 1)
              end
            end

            return {1, stream_id, latest_updated}
            """;

    private static final String LAG_SCRIPT = """
            local groups = redis.call('XINFO', 'GROUPS', KEYS[1])
            local group_info = nil
            for _, fields in ipairs(groups) do
              local candidate = {}
              for index = 1, #fields, 2 do
                candidate[fields[index]] = fields[index + 1]
              end
              if candidate['name'] == ARGV[1] then
                group_info = candidate
                break
              end
            end

            if group_info == nil then
              return redis.error_reply('NOGROUP consumer group does not exist')
            end
            if group_info['lag'] == false or group_info['lag'] == nil then
              return redis.error_reply('LAGUNKNOWN consumer group lag is unavailable')
            end

            local function occurred_at(entry)
              local fields = entry[2]
              for index = 1, #fields, 2 do
                if fields[index] == 'occurredAt' then
                  return fields[index + 1]
                end
              end
              return ''
            end

            local latest_entries = redis.call('XREVRANGE', KEYS[1], '+', '-', 'COUNT', 1)
            local latest_occurred_at = ''
            if #latest_entries > 0 then
              latest_occurred_at = occurred_at(latest_entries[1])
            end

            local last_delivered_id = group_info['last-delivered-id']
            local delivered_occurred_at = ''
            if last_delivered_id ~= '0-0' then
              local delivered_entries = redis.call(
                'XRANGE', KEYS[1], last_delivered_id, last_delivered_id, 'COUNT', 1)
              if #delivered_entries > 0 then
                delivered_occurred_at = occurred_at(delivered_entries[1])
              end
            end

            return {
              tostring(group_info['lag']),
              tostring(last_delivered_id),
              delivered_occurred_at,
              latest_occurred_at}
            """;

    private static final String AVAILABILITY_SCRIPT = """
            local actual = redis.call('TYPE', KEYS[1]).ok
            if actual ~= 'none' and actual ~= 'hash' then
              return redis.error_reply('WRONGTYPE availability key must be hash')
            end
            local stored_sequence = redis.call('HGET', KEYS[1], 'marketSequence')
            local stored_observed_at = redis.call('HGET', KEYS[1], 'observedAt')
            local stored_epoch_second = redis.call('HGET', KEYS[1], 'observedAtEpochSecond')
            local stored_nano = redis.call('HGET', KEYS[1], 'observedAtNano')
            if stored_sequence ~= false then
              if tonumber(ARGV[3]) < tonumber(stored_sequence) then return 0 end
              if tonumber(ARGV[3]) == tonumber(stored_sequence) then
                if stored_epoch_second ~= false and stored_nano ~= false then
                  if tonumber(ARGV[5]) < tonumber(stored_epoch_second) then return 0 end
                  if tonumber(ARGV[5]) == tonumber(stored_epoch_second)
                      and tonumber(ARGV[6]) <= tonumber(stored_nano) then return 0 end
                elseif stored_observed_at ~= false and ARGV[4] == stored_observed_at then
                  return 0
                end
              end
            end
            redis.call('HSET', KEYS[1],
              'schemaVersion', ARGV[1], 'instrumentId', ARGV[2],
              'marketSequence', ARGV[3], 'observedAt', ARGV[4],
              'observedAtEpochSecond', ARGV[5], 'observedAtNano', ARGV[6],
              'status', ARGV[7], 'evaluationAllowed', ARGV[8], 'reasons', ARGV[9])
            return 1
            """;

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final ObjectMapper objectMapper;
    private final String keyBase;
    private final int recentBarCapacity;
    private final int eventStreamCapacity;
    private final Duration deduplicationRetention;

    private RedisMarketEventPublisher(
            RedisClient client,
            String keyPrefix,
            int recentBarCapacity,
            int eventStreamCapacity,
            Duration deduplicationRetention) {
        this.client = Objects.requireNonNull(client, "client");
        this.connection = client.connect();
        this.commands = connection.sync();
        this.objectMapper = new ObjectMapper();
        this.keyBase = keyBase(keyPrefix);
        this.recentBarCapacity = recentBarCapacity(recentBarCapacity);
        this.eventStreamCapacity = positive(eventStreamCapacity, "eventStreamCapacity");
        this.deduplicationRetention = positive(deduplicationRetention, "deduplicationRetention");
    }

    RedisMarketEventPublisher(RedisCommands<String, String> commands, String keyPrefix) {
        this(commands, keyPrefix, 390, 1_000_000, Duration.ofDays(30));
    }

    RedisMarketEventPublisher(
            RedisCommands<String, String> commands, String keyPrefix, int recentBarCapacity) {
        this(commands, keyPrefix, recentBarCapacity, 1_000_000, Duration.ofDays(30));
    }

    RedisMarketEventPublisher(
            RedisCommands<String, String> commands,
            String keyPrefix,
            int recentBarCapacity,
            int eventStreamCapacity,
            Duration deduplicationRetention) {
        this.client = null;
        this.connection = null;
        this.commands = Objects.requireNonNull(commands, "commands");
        this.objectMapper = new ObjectMapper();
        this.keyBase = keyBase(keyPrefix);
        this.recentBarCapacity = recentBarCapacity(recentBarCapacity);
        this.eventStreamCapacity = positive(eventStreamCapacity, "eventStreamCapacity");
        this.deduplicationRetention = positive(deduplicationRetention, "deduplicationRetention");
    }

    public static RedisMarketEventPublisher connect(String redisUri, String keyPrefix) {
        return connect(redisUri, keyPrefix, 390);
    }

    public static RedisMarketEventPublisher connect(
            String redisUri, String keyPrefix, int recentBarCapacity) {
        return connect(redisUri, keyPrefix, recentBarCapacity, 1_000_000, Duration.ofDays(30));
    }

    public static RedisMarketEventPublisher connect(
            String redisUri,
            String keyPrefix,
            int recentBarCapacity,
            int eventStreamCapacity,
            Duration deduplicationRetention) {
        if (redisUri == null || redisUri.isBlank()) {
            throw new IllegalArgumentException("redisUri must not be blank");
        }
        return new RedisMarketEventPublisher(
                RedisClient.create(redisUri), keyPrefix, recentBarCapacity,
                eventStreamCapacity, deduplicationRetention);
    }

    public MarketEventPublishResult publish(MarketEventHandlingResult handlingResult) {
        Objects.requireNonNull(handlingResult, "handlingResult");
        if (!handlingResult.shouldPublish()) {
            return MarketEventPublishResult.skipped();
        }

        MarketEventEnvelope event = handlingResult.event();
        long receivedAtEpochMillis = event.receivedAt().toEpochMilli();
        List<Object> result = evalList(
                PUBLISH_SCRIPT,
                new String[] {
                    streamKey(event.eventType()),
                    latestKey(event.instrumentId(), event.eventType()),
                    deduplicationKey(),
                    recentBarsKey(event.instrumentId(), event.eventType())
                },
                event.eventId(),
                Integer.toString(event.schemaVersion()),
                event.instrumentId().toString(),
                event.provider(),
                event.feed(),
                event.eventType().name(),
                event.providerEventId(),
                event.occurredAt().toString(),
                event.receivedAt().toString(),
                Long.toString(event.sequence()),
                Integer.toString(event.revision()),
                event.correctionOfEventId() == null ? "" : event.correctionOfEventId(),
                serializeValues(event.values()),
                handlingResult.shouldUpdateLatestValue() ? "1" : "0",
                Integer.toString(recentBarCapacity),
                serializeBarUpdate(event),
                Long.toString(receivedAtEpochMillis),
                Long.toString(receivedAtEpochMillis - deduplicationRetention.toMillis()),
                Integer.toString(eventStreamCapacity));

        boolean published = number(result.get(0)) == 1;
        if (!published) {
            return new MarketEventPublishResult(MarketEventPublishStatus.DUPLICATE, "", false);
        }
        return new MarketEventPublishResult(
                MarketEventPublishStatus.PUBLISHED,
                result.get(1).toString(),
                number(result.get(2)) == 1);
    }

    public Optional<MarketEventEnvelope> findLatest(UUID instrumentId, MarketEventType eventType) {
        Map<String, String> fields = commands.hgetall(latestKey(instrumentId, eventType));
        if (fields.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toEnvelope(fields));
    }

    public long streamLength() {
        return commands.xlen(evaluationStreamKey());
    }

    /** Publishes the gateway's C09 result; older or duplicate observations cannot overwrite it. */
    public boolean publishAvailability(
            UUID instrumentId, long marketSequence, Instant observedAt, MarketDataAvailabilityResult result) {
        MarketDataAvailabilityProjection projection =
                MarketDataAvailabilityProjection.from(instrumentId, marketSequence, observedAt, result);
        String reasons = projection.reasons().stream()
                .map(Enum::name)
                .sorted()
                .collect(java.util.stream.Collectors.joining(","));
        Object updated = commands.eval(
                AVAILABILITY_SCRIPT,
                ScriptOutputType.INTEGER,
                new String[] {availabilityKey(instrumentId)},
                Integer.toString(projection.schemaVersion()),
                projection.instrumentId().toString(),
                Long.toString(projection.marketSequence()),
                projection.observedAt().toString(),
                Long.toString(projection.observedAt().getEpochSecond()),
                Integer.toString(projection.observedAt().getNano()),
                projection.status().name(),
                Boolean.toString(projection.evaluationAllowed()),
                reasons);
        return number(updated) == 1;
    }

    public Optional<MarketDataAvailabilityProjection> findAvailability(UUID instrumentId) {
        Map<String, String> fields = commands.hgetall(availabilityKey(instrumentId));
        return fields.isEmpty() ? Optional.empty() : Optional.of(MarketDataAvailabilityEntry.decode(fields));
    }

    public ConsumerLagMeasurement measureConsumerLag(String consumerGroup) {
        if (consumerGroup == null || consumerGroup.isBlank()) {
            throw new IllegalArgumentException("consumerGroup must not be blank");
        }
        List<Object> result = evalList(LAG_SCRIPT, new String[] {evaluationStreamKey()}, consumerGroup);
        long entryLag = number(result.get(0));
        String lastDeliveredId = result.get(1).toString();
        String deliveredOccurredAt = result.get(2).toString();
        String latestOccurredAt = result.get(3).toString();
        Duration observationLag = Duration.ZERO;
        if (!deliveredOccurredAt.isBlank() && !latestOccurredAt.isBlank()) {
            observationLag = Duration.between(
                    Instant.parse(deliveredOccurredAt),
                    Instant.parse(latestOccurredAt));
            if (observationLag.isNegative()) {
                observationLag = Duration.ZERO;
            }
        }
        return new ConsumerLagMeasurement(entryLag, observationLag, lastDeliveredId);
    }

    public String evaluationStreamKey() {
        return keyBase + ":strategy:evaluation-ready:v1";
    }

    /** The worker-facing stream is now evaluation-only. */
    public String streamKey() {
        return evaluationStreamKey();
    }

    public String candleStreamKey() {
        return keyBase + ":strategy:candles:v1";
    }

    private String streamKey(MarketEventType eventType) {
        return switch (eventType) {
            case MARKET_EVALUATION_READY -> evaluationStreamKey();
            case BAR_30M, BAR_1H, BAR_4H, BAR_1D -> candleStreamKey();
            default -> keyBase + ":events";
        };
    }

    String deduplicationKey() {
        return keyBase + ":seen:v2";
    }

    String latestKey(UUID instrumentId, MarketEventType eventType) {
        Objects.requireNonNull(instrumentId, "instrumentId");
        Objects.requireNonNull(eventType, "eventType");
        return keyBase + ":latest:" + instrumentId + ":" + eventType.name();
    }

    public String recentBarsKey(UUID instrumentId, MarketEventType eventType) {
        Objects.requireNonNull(instrumentId, "instrumentId");
        Objects.requireNonNull(eventType, "eventType");
        String timeframe = switch (eventType) {
            case BAR_30M -> "30m";
            case BAR_1H -> "1h";
            case BAR_4H -> "4h";
            case BAR_1D -> "1d";
            default -> "none";
        };
        return keyBase + ":bars:" + instrumentId + ":" + timeframe;
    }

    public String availabilityKey(UUID instrumentId) {
        Objects.requireNonNull(instrumentId, "instrumentId");
        return keyBase + ":availability:" + instrumentId;
    }

    public static String availabilityKey(String keyPrefix, UUID instrumentId) {
        Objects.requireNonNull(instrumentId, "instrumentId");
        return keyBase(keyPrefix) + ":availability:" + instrumentId;
    }

    @Override
    public void close() {
        if (connection != null) {
            connection.close();
        }
        if (client != null) {
            client.shutdown();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> evalList(String script, String[] keys, String... values) {
        return (List<Object>) commands.eval(script, ScriptOutputType.MULTI, keys, values);
    }

    /** One decoder for the layout this class writes; see {@link MarketEventStreamEntry}. */
    private MarketEventEnvelope toEnvelope(Map<String, String> fields) {
        return MarketEventStreamEntry.decode(fields);
    }

    private String serializeValues(Map<String, BigDecimal> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("market event values cannot be serialized", exception);
        }
    }

    private String serializeBarUpdate(MarketEventEnvelope event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", event.schemaVersion());
        payload.put("eventId", event.eventId());
        payload.put("instrumentId", event.instrumentId().toString());
        payload.put("provider", event.provider());
        payload.put("feed", event.feed());
        payload.put("eventType", event.eventType().name());
        payload.put("occurredAt", event.occurredAt().toString());
        payload.put("receivedAt", event.receivedAt().toString());
        payload.put("sequence", event.sequence());
        payload.put("revision", event.revision());
        payload.putAll(event.values());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("market bar update cannot be serialized", exception);
        }
    }

    private static int recentBarCapacity(int value) {
        if (value < 1 || value > 10_000) {
            throw new IllegalArgumentException("recentBarCapacity must be between 1 and 10000");
        }
        return value;
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static String keyBase(String keyPrefix) {
        if (keyPrefix == null || keyPrefix.isBlank()) {
            throw new IllegalArgumentException("keyPrefix must not be blank");
        }
        if (keyPrefix.indexOf('{') >= 0 || keyPrefix.indexOf('}') >= 0) {
            throw new IllegalArgumentException("keyPrefix must not contain Redis hash-tag braces");
        }
        return "{" + keyPrefix + ":market}";
    }

    private static String required(Map<String, String> fields, String name) {
        String value = fields.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("latest market event is missing " + name);
        }
        return value;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString());
    }
}
