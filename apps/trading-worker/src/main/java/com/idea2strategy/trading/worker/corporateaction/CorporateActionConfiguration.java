package com.idea2strategy.trading.worker.corporateaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.trading.application.corporateaction.CorporateActionService;
import com.idea2strategy.trading.application.port.BotEventStore;
import com.idea2strategy.trading.application.port.CorporateActionStore;
import com.idea2strategy.trading.worker.lifecycle.RuntimeIntakeGate;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;

/** Runtime wiring for the approved D-to-F corporate action application (F92). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class CorporateActionConfiguration {

    @Bean
    CorporateActionService corporateActionService(CorporateActionStore store) {
        return new CorporateActionService(store);
    }

    @Bean
    @ConditionalOnProperty(name = "trading.corporate-action-apply.enabled", havingValue = "true")
    PollingWorker approvedCorporateActionWorker(
            JdbcClient jdbc, BotEventStore events, CorporateActionService service,
            PlatformTransactionManager transactionManager, RuntimeIntakeGate intakeGate,
            Environment environment) {
        ApprovedCorporateActionPoller poller = new ApprovedCorporateActionPoller(
                jdbc, events, service, new ObjectMapper(), Clock.systemUTC(), transactionManager);
        return new PollingWorker(poller, intakeGate,
                environment.getProperty("trading.corporate-action-apply.batch-size",
                        Integer.class, 8));
    }

    public static final class PollingWorker {
        private final ApprovedCorporateActionPoller poller;
        private final RuntimeIntakeGate intakeGate;
        private final int batchSize;

        PollingWorker(ApprovedCorporateActionPoller poller, RuntimeIntakeGate intakeGate,
                int batchSize) {
            this.poller = poller;
            this.intakeGate = intakeGate;
            if (batchSize < 1) {
                throw new IllegalArgumentException("batchSize must be positive");
            }
            this.batchSize = batchSize;
        }

        @Scheduled(fixedDelayString = "${trading.corporate-action-apply.poll-delay:PT30S}")
        public void poll() {
            intakeGate.runIfOpen(() -> poller.pollOnce(batchSize));
        }
    }
}
