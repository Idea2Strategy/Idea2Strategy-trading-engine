package com.idea2strategy.trading.worker.roomevaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.trading.application.port.BotEventStore;
import com.idea2strategy.trading.application.port.LedgerStore;
import com.idea2strategy.trading.worker.lifecycle.RuntimeIntakeGate;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;

/** Runtime wiring for the approved E-to-F room ledger handoff. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class RoomEvaluationAccountOpenConfiguration {

    @Bean
    @ConditionalOnProperty(name = "trading.room-account-open.enabled", havingValue = "true")
    PollingWorker roomEvaluationAccountOpenWorker(
            JdbcClient jdbc, BotEventStore events, LedgerStore ledger,
            PlatformTransactionManager transactionManager, RuntimeIntakeGate intakeGate,
            Environment environment) {
        RoomEvaluationAccountOpenPoller poller = new RoomEvaluationAccountOpenPoller(
                jdbc, events, ledger, new ObjectMapper(), Clock.systemUTC(),
                environment.getProperty("trading.room-account-open.worker-id", "trading-worker"),
                environment.getProperty("trading.room-account-open.lease", Duration.class, Duration.ofSeconds(30)),
                environment.getProperty("trading.room-account-open.retry-backoff", Duration.class, Duration.ofSeconds(30)),
                environment.getProperty("trading.room-account-open.max-attempts", Integer.class, 5),
                transactionManager);
        return new PollingWorker(poller, intakeGate,
                environment.getProperty("trading.room-account-open.batch-size", Integer.class, 16));
    }

    public static final class PollingWorker {
        private final RoomEvaluationAccountOpenPoller poller;
        private final RuntimeIntakeGate intakeGate;
        private final int batchSize;

        PollingWorker(RoomEvaluationAccountOpenPoller poller, RuntimeIntakeGate intakeGate, int batchSize) {
            this.poller = poller;
            this.intakeGate = intakeGate;
            if (batchSize < 1) throw new IllegalArgumentException("batchSize must be positive");
            this.batchSize = batchSize;
        }

        @Scheduled(fixedDelayString = "${trading.room-account-open.poll-delay:PT1S}")
        public void poll() {
            intakeGate.runIfOpen(() -> poller.pollOnce(batchSize));
        }
    }
}
