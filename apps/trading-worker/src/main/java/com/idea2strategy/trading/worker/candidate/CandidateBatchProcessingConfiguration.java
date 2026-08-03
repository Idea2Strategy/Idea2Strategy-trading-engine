package com.idea2strategy.trading.worker.candidate;

import com.idea2strategy.trading.application.candidate.CandidateBatchProcessor;
import com.idea2strategy.trading.application.port.BotStopSettlementStore;
import com.idea2strategy.trading.application.port.CandidateBatchClaimPort;
import com.idea2strategy.trading.application.port.CandidateBatchStatusPort;
import com.idea2strategy.trading.application.port.ExecutionPort;
import com.idea2strategy.trading.application.port.OrderPort;
import com.idea2strategy.trading.application.port.SettlementPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CandidateBatchProcessingConfiguration {

    @Bean
    CandidateBatchProcessor candidateBatchProcessor(
            CandidateBatchClaimPort claimPort,
            CandidateBatchStatusPort statusPort,
            OrderPort orderPort,
            ExecutionPort executionPort,
            SettlementPort settlementPort,
            BotStopSettlementStore stopSettlements) {
        return new CandidateBatchProcessor(
                claimPort, statusPort, orderPort, executionPort, settlementPort, stopSettlements);
    }
}
