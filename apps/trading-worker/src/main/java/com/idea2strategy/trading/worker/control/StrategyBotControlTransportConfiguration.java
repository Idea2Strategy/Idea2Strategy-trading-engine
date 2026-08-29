package com.idea2strategy.trading.worker.control;

import com.idea2strategy.trading.strategy.runtime.control.StrategyBotControlConsumer;
import com.idea2strategy.trading.worker.lifecycle.RuntimeIntakeGate;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Wires B's command transport (RT5) when the consumer it feeds is actually present.
 *
 * <p>The consumer is a required dependency, so a worker that cannot settle stop commands fails at
 * startup instead of silently leaving commands unread. Runtime lifecycle admission is used when it
 * is installed; narrowly scoped test or maintenance contexts without that coordinator still consume
 * stop commands rather than coupling safety shutdown to the optional warm-up path.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class StrategyBotControlTransportConfiguration {

    /**
     * The handler identity the receipts are keyed by. It names the consumer, not the process, so two
     * replicas share one receipt per message and neither redelivers what the other completed.
     */
    public static final String HANDLER_ID = "trading-worker.strategy-bot-control";

    @Bean
    @ConditionalOnProperty(name = "trading.bot-control.transport.enabled", havingValue = "true")
    StrategyBotOutboxPollingWorker strategyBotOutboxPollingWorker(
            JdbcClient jdbc,
            StrategyBotControlConsumer consumer,
            ObjectProvider<RuntimeIntakeGate> intakeGate,
            Environment environment) {
        return new StrategyBotOutboxPollingWorker(new StrategyBotOutboxPoller(
                jdbc,
                consumer,
                Clock.systemUTC(),
                HANDLER_ID,
                environment.getProperty("trading.bot-control.worker-id", "trading-worker"),
                environment.getProperty("trading.bot-control.batch-size", Integer.class, 32),
                environment.getProperty("trading.bot-control.lease", Duration.class, Duration.ofSeconds(30)),
                environment.getProperty("trading.bot-control.retry-backoff", Duration.class, Duration.ofSeconds(30)),
                environment.getProperty("trading.bot-control.max-attempts", Integer.class, 5)),
                intakeGate.getIfAvailable());
    }

    /** The schedule around one {@link StrategyBotOutboxPoller#pollOnce()} cycle. */
    public static final class StrategyBotOutboxPollingWorker {
        private final StrategyBotOutboxPoller poller;
        private final RuntimeIntakeGate intakeGate;

        StrategyBotOutboxPollingWorker(StrategyBotOutboxPoller poller, RuntimeIntakeGate intakeGate) {
            this.poller = poller;
            this.intakeGate = intakeGate;
        }

        @Scheduled(fixedDelayString = "${trading.bot-control.poll-delay:PT1S}")
        public void poll() {
            if (intakeGate == null) {
                poller.pollOnce();
            } else {
                intakeGate.runIfOpen(poller::pollOnce);
            }
        }
    }
}
