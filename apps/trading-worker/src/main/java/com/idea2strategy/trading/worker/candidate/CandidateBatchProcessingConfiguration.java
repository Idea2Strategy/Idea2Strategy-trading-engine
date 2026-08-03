package com.idea2strategy.trading.worker.candidate;

import com.idea2strategy.trading.application.candidate.CandidateBatchProcessor;
import com.idea2strategy.trading.application.candidate.ScopedCandidateComposer;
import com.idea2strategy.trading.application.port.BotStopSettlementStore;
import com.idea2strategy.trading.application.port.CandidateBatchClaimPort;
import com.idea2strategy.trading.application.port.CandidateBatchStatusPort;
import com.idea2strategy.trading.application.port.ExecutionPort;
import com.idea2strategy.trading.application.port.OrderPort;
import com.idea2strategy.trading.application.port.SettlementPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CandidateBatchProcessingConfiguration {

    /**
     * The fixture pipeline ports exist only while {@code trading.fake-candidate.enabled} is on, so
     * they arrive as providers: a production worker runs with the composer alone, and an unscoped
     * (version 1, fixture-only) batch without the fakes fails loudly in the processor.
     */
    @Bean
    CandidateBatchProcessor candidateBatchProcessor(
            CandidateBatchClaimPort claimPort,
            CandidateBatchStatusPort statusPort,
            ScopedCandidateComposer composer,
            ObjectProvider<OrderPort> orderPort,
            ObjectProvider<ExecutionPort> executionPort,
            ObjectProvider<SettlementPort> settlementPort,
            BotStopSettlementStore stopSettlements) {
        return new CandidateBatchProcessor(
                claimPort, statusPort, composer,
                orderPort.getIfAvailable(), executionPort.getIfAvailable(),
                settlementPort.getIfAvailable(), stopSettlements);
    }
}
