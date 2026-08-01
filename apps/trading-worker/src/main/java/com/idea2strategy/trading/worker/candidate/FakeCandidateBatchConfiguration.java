package com.idea2strategy.trading.worker.candidate;

import com.idea2strategy.trading.application.candidate.CandidateBatchProcessor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "trading.fake-candidate", name = "enabled", havingValue = "true")
public class FakeCandidateBatchConfiguration {

    @Bean
    FakeCandidateBatchSource fakeCandidateBatchSource() {
        return new FakeCandidateBatchSource();
    }

    @Bean
    ApplicationRunner fakeCandidateBatchRunner(
            FakeCandidateBatchSource source,
            OrderCandidateBatchAdapter adapter,
            CandidateBatchProcessor processor) {
        return ignored -> processor.process(adapter.toDomain(source.nextBatch()));
    }
}
