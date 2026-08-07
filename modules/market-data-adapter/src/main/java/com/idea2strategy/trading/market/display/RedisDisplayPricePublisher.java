package com.idea2strategy.trading.market.display;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.trading.messaging.market.DisplayPriceUpdate;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Display-only Redis Pub/Sub publisher. This class intentionally has no Redis Stream writes. */
public final class RedisDisplayPricePublisher implements AutoCloseable {
    private static final String PUBLISH_SCRIPT = """
            local function assert_type(key, expected)
              local actual = redis.call('TYPE', key).ok
              if actual ~= 'none' and actual ~= expected then
                return redis.error_reply('WRONGTYPE key ' .. key .. ' must be ' .. expected)
              end
            end
            local type_error = assert_type(KEYS[1], 'hash')
            if type_error ~= nil then return type_error end
            type_error = assert_type(KEYS[2], 'hash')
            if type_error ~= nil then return type_error end
            type_error = assert_type(KEYS[3], 'zset')
            if type_error ~= nil then return type_error end

            redis.call('HSET', KEYS[1],
              'instrumentId', ARGV[2], 'symbol', ARGV[3], 'price', ARGV[4],
              'occurredAt', ARGV[5], 'publishedAt', ARGV[6], 'payload', ARGV[1])

            local bucket = ARGV[7]
            local incoming = cjson.decode(ARGV[1])
            local existing_json = redis.call('HGET', KEYS[2], bucket)
            local bar
            if existing_json == false then
              bar = {
                schemaVersion = 1,
                eventId = 'display:' .. ARGV[2] .. ':' .. bucket,
                instrumentId = ARGV[2], provider = 'ALPACA', feed = 'SIP',
                eventType = 'BAR_1M', occurredAt = ARGV[8],
                sequence = tonumber(bucket), revision = 0,
                open = incoming.intervalOpen, high = incoming.intervalHigh,
                low = incoming.intervalLow, close = incoming.intervalClose,
                volume = incoming.intervalVolume,
                tradeCount = incoming.intervalTradeCount
              }
            else
              bar = cjson.decode(existing_json)
              bar.high = math.max(bar.high, incoming.intervalHigh)
              bar.low = math.min(bar.low, incoming.intervalLow)
              bar.close = incoming.intervalClose
              bar.volume = bar.volume + incoming.intervalVolume
              bar.tradeCount = bar.tradeCount + incoming.intervalTradeCount
            end
            redis.call('HSET', KEYS[2], bucket, cjson.encode(bar))
            redis.call('ZADD', KEYS[3], tonumber(bucket), bucket)

            local count = redis.call('ZCARD', KEYS[3])
            local capacity = tonumber(ARGV[9])
            if count > capacity then
              local stale = redis.call('ZRANGE', KEYS[3], 0, count - capacity - 1)
              if #stale > 0 then
                redis.call('HDEL', KEYS[2], unpack(stale))
                redis.call('ZREM', KEYS[3], unpack(stale))
              end
            end
            redis.call('PUBLISH', ARGV[10], ARGV[1])
            return 1
            """;

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String keyBase;
    private final int minuteBarCapacity;

    private RedisDisplayPricePublisher(RedisClient client, String keyPrefix, int minuteBarCapacity) {
        this.client = Objects.requireNonNull(client, "client");
        this.connection = client.connect();
        this.commands = connection.sync();
        this.keyBase = keyBase(keyPrefix);
        this.minuteBarCapacity = positive(minuteBarCapacity);
    }

    RedisDisplayPricePublisher(RedisCommands<String, String> commands, String keyPrefix) {
        this(commands, keyPrefix, 10_000);
    }

    RedisDisplayPricePublisher(
            RedisCommands<String, String> commands, String keyPrefix, int minuteBarCapacity) {
        this.client = null;
        this.connection = null;
        this.commands = Objects.requireNonNull(commands, "commands");
        this.keyBase = keyBase(keyPrefix);
        this.minuteBarCapacity = positive(minuteBarCapacity);
    }

    public static RedisDisplayPricePublisher connect(String redisUri, String keyPrefix) {
        return connect(redisUri, keyPrefix, 10_000);
    }

    public static RedisDisplayPricePublisher connect(
            String redisUri, String keyPrefix, int minuteBarCapacity) {
        return new RedisDisplayPricePublisher(
                RedisClient.create(redisUri), keyPrefix, minuteBarCapacity);
    }

    public void publish(DisplayPriceUpdate update) {
        Objects.requireNonNull(update, "update");
        String json = json(update);
        long minute = Math.floorDiv(update.occurredAt().getEpochSecond(), 60L) * 60L;
        commands.eval(
                PUBLISH_SCRIPT,
                ScriptOutputType.INTEGER,
                new String[] {
                    latestKey(update.instrumentId().toString()),
                    minuteBarsKey(update.instrumentId().toString()),
                    minuteBarIndexKey(update.instrumentId().toString())
                },
                json,
                update.instrumentId().toString(),
                update.symbol(),
                update.price().toPlainString(),
                update.occurredAt().toString(),
                update.publishedAt().toString(),
                Long.toString(minute),
                java.time.Instant.ofEpochSecond(minute).toString(),
                Integer.toString(minuteBarCapacity),
                updatesChannel());
    }

    public String updatesChannel() {
        return keyBase + ":display:price-updates";
    }

    private String latestKey(String instrumentId) {
        return keyBase + ":display:latest:" + instrumentId;
    }

    String minuteBarsKey(String instrumentId) {
        return keyBase + ":display:bars:1m:" + instrumentId;
    }

    String minuteBarIndexKey(String instrumentId) {
        return keyBase + ":display:bar-index:1m:" + instrumentId;
    }

    private String json(DisplayPriceUpdate update) {
        try {
            return mapper.writeValueAsString(payload(update));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("display price update cannot be serialized", exception);
        }
    }

    static Map<String, Object> payload(DisplayPriceUpdate update) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("instrumentId", update.instrumentId().toString());
        payload.put("symbol", update.symbol());
        payload.put("price", update.price());
        payload.put("lastTradeSize", update.lastTradeSize());
        payload.put("intervalOpen", update.intervalOpen());
        payload.put("intervalHigh", update.intervalHigh());
        payload.put("intervalLow", update.intervalLow());
        payload.put("intervalClose", update.intervalClose());
        payload.put("intervalVolume", update.intervalVolume());
        payload.put("intervalTradeCount", update.intervalTradeCount());
        payload.put("providerTradeId", update.providerTradeId());
        payload.put("occurredAt", update.occurredAt().toString());
        payload.put("publishedAt", update.publishedAt().toString());
        return Map.copyOf(payload);
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

    private static String keyBase(String prefix) {
        if (prefix == null || prefix.isBlank() || prefix.contains("{") || prefix.contains("}")) {
            throw new IllegalArgumentException("keyPrefix must be a plain non-empty value");
        }
        return "{" + prefix + ":market}";
    }

    private static int positive(int value) {
        if (value < 1 || value > 100_000) {
            throw new IllegalArgumentException("minuteBarCapacity must be between 1 and 100000");
        }
        return value;
    }
}
