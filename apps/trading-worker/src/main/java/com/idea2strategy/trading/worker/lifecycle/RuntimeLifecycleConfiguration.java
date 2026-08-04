package com.idea2strategy.trading.worker.lifecycle;

import com.idea2strategy.trading.common.runtime.FileReadinessMarker;
import com.idea2strategy.trading.application.stop.BotStopOrchestrator;
import java.nio.file.Path;
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
    FileReadinessMarker tradingWorkerReadinessMarker(Environment environment) {
        return new FileReadinessMarker(Path.of(environment.getProperty(
                "i2s.readiness-file", "/tmp/idea2strategy-ready")));
    }

    @Bean
    RuntimeLifecycleCoordinator runtimeLifecycleCoordinator(
            RuntimeIntakeGate gate,
            FileReadinessMarker readinessMarker,
            ObjectProvider<BotStopOrchestrator> orchestrator,
            Environment environment) {
        return new RuntimeLifecycleCoordinator(
                gate,
                readinessMarker,
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
