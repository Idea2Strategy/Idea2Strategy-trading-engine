package com.idea2strategy.trading.worker.market;

import com.idea2strategy.trading.worker.lifecycle.RuntimeIntakeGate;
import com.idea2strategy.trading.worker.runtime.EvaluatingBotRuntime;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * RT3: wires the gateway's market event stream into the evaluation loop.
 *
 * <p>Gated on a configured Redis URI, mirroring the gateway's own publisher gate: a worker with no
 * market stream configured starts and runs its other duties rather than failing to wire. It is a
 * property rather than a bean condition for the reason recorded on the bot control configuration —
 * outside an auto-configuration a bean condition is decided by registration order, and that must not be
 * what decides whether a worker sees the market.
 *
 * <p>The key prefix must match the gateway's {@code market-gateway.redis-key-prefix}, because the
 * stream key is derived from it on both sides. Getting it wrong produces an empty stream rather than an
 * error, so it is worth checking first when a worker is running and evaluating nothing.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "trading.market-events", name = "redis-uri")
public class MarketEventTransportConfiguration {

    @Bean(destroyMethod = "shutdown")
    RedisClient marketEventRedisClient(Environment environment) {
        return RedisClient.create(
                RedisURI.create(environment.getRequiredProperty("trading.market-events.redis-uri")));
    }

    @Bean(destroyMethod = "close")
    StatefulRedisConnection<String, String> marketEventRedisConnection(RedisClient client) {
        return client.connect();
    }

    @Bean
    RedisMarketEventStreamConsumer marketEventStreamConsumer(
            StatefulRedisConnection<String, String> connection,
            EvaluatingBotRuntime runtime,
            Environment environment) {
        String prefix = environment.getProperty("trading.market-events.redis-key-prefix", "i2s");
        var availabilityPolicy = new RedisProjectedMarketAvailabilityPolicy(
                connection.sync(),
                prefix,
                Clock.systemUTC(),
                environment.getProperty(
                        "trading.market-events.maximum-availability-age",
                        Duration.class,
                        Duration.ofMinutes(2)));
        return new RedisMarketEventStreamConsumer(
                connection.sync(),
                runtime,
                "{" + prefix + ":market}:events",
                environment.getProperty(
                        "trading.market-events.consumer-name",
                        "worker-" + java.util.UUID.randomUUID()),
                environment.getProperty("trading.market-events.batch-size", Integer.class, 128),
                environment.getProperty(
                        "trading.market-events.reclaim-after", Duration.class, Duration.ofSeconds(60)),
                environment.getProperty("trading.market-events.maximum-entry-lag", Long.class, 600L),
                availabilityPolicy);
    }

    @Bean
    MarketEventPollingWorker marketEventPollingWorker(
            RedisMarketEventStreamConsumer consumer, RuntimeIntakeGate intakeGate) {
        return new MarketEventPollingWorker(consumer, intakeGate);
    }

    /**
     * The schedule around one cycle.
     *
     * <p>The default delay is short because the delay is added to every bar's latency before a bot can
     * act on it, and the consumer's own lag guard is what protects the system when it cannot keep up.
     */
    public static final class MarketEventPollingWorker {
        private final RedisMarketEventStreamConsumer consumer;
        private final RuntimeIntakeGate intakeGate;

        MarketEventPollingWorker(
                RedisMarketEventStreamConsumer consumer, RuntimeIntakeGate intakeGate) {
            this.consumer = consumer;
            this.intakeGate = intakeGate;
        }

        @Scheduled(fixedDelayString = "${trading.market-events.poll-delay:PT0.2S}")
        public void poll() {
            intakeGate.runIfOpen(consumer::pollOnce);
        }
    }
}
