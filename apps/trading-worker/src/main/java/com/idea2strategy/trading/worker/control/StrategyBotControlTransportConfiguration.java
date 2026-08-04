package com.idea2strategy.trading.worker.control;

import com.idea2strategy.trading.strategy.runtime.control.StrategyBotControlConsumer;
import com.idea2strategy.trading.worker.lifecycle.RuntimeIntakeGate;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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
 * <p>The poller stays gated on {@link StrategyBotControlConsumer} rather than declared
 * unconditionally, so a worker whose stop settlement ports are absent starts and runs its other
 * duties instead of polling commands it could not carry out. Since root #190 gave the compiled-plan
 * contract a producer and B91 wired the consumer, that gate is normally satisfied — a worker that is
 * not polling is now a signal worth reading, not the expected state.
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
    @ConditionalOnBean(StrategyBotControlConsumer.class)
    @ConditionalOnProperty(name = "trading.bot-control.transport.enabled", matchIfMissing = true)
    StrategyBotOutboxPollingWorker strategyBotOutboxPollingWorker(
            JdbcClient jdbc,
            StrategyBotControlConsumer consumer,
            RuntimeIntakeGate intakeGate,
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
                intakeGate);
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
            intakeGate.runIfOpen(poller::pollOnce);
        }
    }
}
