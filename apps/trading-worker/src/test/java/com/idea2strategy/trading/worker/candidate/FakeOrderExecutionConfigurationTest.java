package com.idea2strategy.trading.worker.candidate;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.trading.application.port.ExecutionPort;
import com.idea2strategy.trading.application.port.OrderPort;
import com.idea2strategy.trading.application.port.SettlementPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class FakeOrderExecutionConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(FakeOrderExecutionConfiguration.class);

    @Test
    void fakeDownstreamPortsAreDisabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(OrderPort.class);
            assertThat(context).doesNotHaveBean(ExecutionPort.class);
            assertThat(context).doesNotHaveBean(SettlementPort.class);
        });
    }

    @Test
    void fakeDownstreamPortsRequireExplicitOptIn() {
        contextRunner
                .withPropertyValues("trading.fake-candidate.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(OrderPort.class);
                    assertThat(context).hasSingleBean(ExecutionPort.class);
                    assertThat(context).hasSingleBean(SettlementPort.class);
                });
    }
}
