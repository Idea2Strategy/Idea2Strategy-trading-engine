package com.idea2strategy.trading.worker.stop;

import com.idea2strategy.trading.application.port.BotExecutionGatePort;
import com.idea2strategy.trading.application.port.BotStopSettlementStore;
import com.idea2strategy.trading.application.port.OpenOrderCleanupPort;
import com.idea2strategy.trading.application.port.PositionLiquidationPort;
import com.idea2strategy.trading.application.stop.BotStopOrchestrator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class BotStopSettlementConfiguration {
    @Bean
    @ConditionalOnBean({
            BotExecutionGatePort.class,
            OpenOrderCleanupPort.class,
            PositionLiquidationPort.class
    })
    BotStopOrchestrator botStopOrchestrator(
            BotStopSettlementStore store,
            BotExecutionGatePort executionGate,
            OpenOrderCleanupPort orderCleanup,
            PositionLiquidationPort liquidation) {
        return new BotStopOrchestrator(store, executionGate, orderCleanup, liquidation);
    }

    @Bean
    @ConditionalOnBean(BotStopOrchestrator.class)
    BotStopRecoveryWorker botStopRecoveryWorker(BotStopOrchestrator orchestrator) {
        return new BotStopRecoveryWorker(orchestrator);
    }
}
