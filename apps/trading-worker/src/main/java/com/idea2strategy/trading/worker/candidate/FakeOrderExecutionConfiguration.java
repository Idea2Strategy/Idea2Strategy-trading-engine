package com.idea2strategy.trading.worker.candidate;

import com.idea2strategy.trading.application.port.ExecutionPort;
import com.idea2strategy.trading.application.port.OrderPort;
import com.idea2strategy.trading.application.port.SettlementPort;
import com.idea2strategy.trading.domain.execution.Execution;
import com.idea2strategy.trading.domain.order.Order;
import com.idea2strategy.trading.domain.settlement.Settlement;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "trading.fake-candidate", name = "enabled", havingValue = "true")
public class FakeOrderExecutionConfiguration {

    @Bean
    OrderPort fakeOrderPort() {
        return candidate -> new Order(
                stableId("order", candidate.candidateId()),
                candidate.candidateId(),
                candidate.instrumentId(),
                candidate.side(),
                candidate.quantity(),
                candidate.limitPrice());
    }

    @Bean
    ExecutionPort fakeExecutionPort() {
        return order -> new Execution(
                stableId("execution", order.orderId()),
                order.orderId(),
                order.quantity(),
                order.limitPrice() == null ? BigDecimal.ONE : order.limitPrice());
    }

    @Bean
    SettlementPort fakeSettlementPort() {
        return execution -> new Settlement(
                stableId("settlement", execution.executionId()),
                execution.executionId());
    }

    private static UUID stableId(String kind, UUID sourceId) {
        return UUID.nameUUIDFromBytes((kind + ":" + sourceId).getBytes(StandardCharsets.UTF_8));
    }
}
