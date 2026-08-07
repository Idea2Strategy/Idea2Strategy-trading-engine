package com.idea2strategy.trading.market.display;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Reads renewable browser subscription leases so the single Alpaca socket follows active charts. */
public final class RedisDisplayTradeSubscriptionSource implements DisplayTradeSubscriptionSource, AutoCloseable {
    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final String leasesKey;

    private RedisDisplayTradeSubscriptionSource(RedisClient client, String keyPrefix) {
        this.client = Objects.requireNonNull(client, "client");
        this.connection = client.connect();
        this.commands = connection.sync();
        this.leasesKey = "{" + keyPrefix + ":market}:display:subscription-leases";
    }

    public static RedisDisplayTradeSubscriptionSource connect(String redisUri, String keyPrefix) {
        return new RedisDisplayTradeSubscriptionSource(RedisClient.create(redisUri), keyPrefix);
    }

    @Override
    public Set<String> desiredSymbols(Instant now) {
        Objects.requireNonNull(now, "now");
        double epochMillis = now.toEpochMilli();
        commands.zremrangebyscore(leasesKey, 0, epochMillis);
        LinkedHashSet<String> symbols = new LinkedHashSet<>();
        for (String lease : commands.zrangebyscore(leasesKey, epochMillis, Double.POSITIVE_INFINITY)) {
            int separator = lease.lastIndexOf('|');
            if (separator >= 0 && separator + 1 < lease.length()) {
                symbols.add(lease.substring(separator + 1).trim().toUpperCase(Locale.ROOT));
            }
        }
        return Set.copyOf(symbols);
    }

    public String leasesKey() {
        return leasesKey;
    }

    @Override
    public void close() {
        connection.close();
        client.shutdown();
    }
}
