package com.idea2strategy.trading.worker.lifecycle;

import com.idea2strategy.trading.application.stop.BotStopOrchestrator;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "trading.runtime.lifecycle.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class RuntimeLifecycleConfiguration {
    @Bean
    RuntimeIntakeGate runtimeIntakeGate() {
        return new RuntimeIntakeGate();
    }

    @Bean
    RuntimeLifecycleCoordinator runtimeLifecycleCoordinator(
            RuntimeIntakeGate gate,
            ObjectProvider<BotStopOrchestrator> orchestrator,
            Environment environment) {
        return new RuntimeLifecycleCoordinator(
                gate,
                now -> {
                    BotStopOrchestrator available = orchestrator.getIfAvailable();
                    if (available != null) {
                        available.resumeRecoverable(now);
                    }
                },
                Clock.systemUTC(),
                environment.getProperty(
                        "trading.runtime.shutdown-drain-timeout",
                        Duration.class,
                        Duration.ofSeconds(30)));
    }
}
