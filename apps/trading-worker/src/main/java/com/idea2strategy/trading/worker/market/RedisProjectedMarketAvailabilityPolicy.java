package com.idea2strategy.trading.worker.market;

import com.idea2strategy.trading.market.availability.MarketDataAvailabilityStatus;
import com.idea2strategy.trading.market.redis.MarketDataAvailabilityEntry;
import com.idea2strategy.trading.market.redis.RedisMarketEventPublisher;
import com.idea2strategy.trading.messaging.market.MarketEventEnvelope;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Fail-closed worker view of the gateway's instrument-keyed C09 decision. */
public final class RedisProjectedMarketAvailabilityPolicy implements MarketEventAvailabilityPolicy {
    private final RedisCommands<String, String> commands;
    private final String keyPrefix;
    private final Clock clock;
    private final Duration maximumAge;

    public RedisProjectedMarketAvailabilityPolicy(
            RedisCommands<String, String> commands, String keyPrefix, Clock clock, Duration maximumAge) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.keyPrefix = requireText(keyPrefix, "keyPrefix");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maximumAge = Objects.requireNonNull(maximumAge, "maximumAge");
        if (maximumAge.isZero() || maximumAge.isNegative()) {
            throw new IllegalArgumentException("maximumAge must be positive");
        }
    }

    @Override
    public boolean permits(MarketEventEnvelope event) {
        Objects.requireNonNull(event, "event");
        try {
            Map<String, String> fields = commands.hgetall(
                    RedisMarketEventPublisher.availabilityKey(keyPrefix, event.instrumentId()));
            if (fields == null || fields.isEmpty()) {
                return false;
            }
            var projection = MarketDataAvailabilityEntry.decode(fields);
            return projection.instrumentId().equals(event.instrumentId())
                    && projection.marketSequence() >= event.sequence()
                    && !projection.observedAt().isAfter(clock.instant())
                    && !projection.observedAt().plus(maximumAge).isBefore(clock.instant())
                    && projection.status() == MarketDataAvailabilityStatus.AVAILABLE
                    && projection.evaluationAllowed();
        } catch (RuntimeException malformedOrUnavailable) {
            return false;
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
