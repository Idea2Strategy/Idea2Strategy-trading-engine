package com.idea2strategy.trading.market.display;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.trading.messaging.market.DisplayPriceUpdate;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Display-only Redis Pub/Sub publisher. This class intentionally has no Redis Stream writes. */
public final class RedisDisplayPricePublisher implements AutoCloseable {
    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String keyBase;

    private RedisDisplayPricePublisher(RedisClient client, String keyPrefix) {
        this.client = Objects.requireNonNull(client, "client");
        this.connection = client.connect();
        this.commands = connection.sync();
        this.keyBase = keyBase(keyPrefix);
    }

    RedisDisplayPricePublisher(RedisCommands<String, String> commands, String keyPrefix) {
        this.client = null;
        this.connection = null;
        this.commands = Objects.requireNonNull(commands, "commands");
        this.keyBase = keyBase(keyPrefix);
    }

    public static RedisDisplayPricePublisher connect(String redisUri, String keyPrefix) {
        return new RedisDisplayPricePublisher(RedisClient.create(redisUri), keyPrefix);
    }

    public void publish(DisplayPriceUpdate update) {
        Objects.requireNonNull(update, "update");
        String json = json(update);
        commands.hset(latestKey(update.instrumentId().toString()), Map.of(
                "instrumentId", update.instrumentId().toString(),
                "symbol", update.symbol(),
                "price", update.price().toPlainString(),
                "occurredAt", update.occurredAt().toString(),
                "publishedAt", update.publishedAt().toString(),
                "payload", json));
        commands.publish(updatesChannel(), json);
    }

    public String updatesChannel() {
        return keyBase + ":display:price-updates";
    }

    private String latestKey(String instrumentId) {
        return keyBase + ":display:latest:" + instrumentId;
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
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("keyPrefix must not be blank");
        }
        return "{" + prefix + ":market}";
    }
}
