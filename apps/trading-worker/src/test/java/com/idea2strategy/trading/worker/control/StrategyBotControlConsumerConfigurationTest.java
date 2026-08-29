package com.idea2strategy.trading.worker.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.trading.strategy.runtime.control.BotControlFailure;
import com.idea2strategy.trading.strategy.runtime.control.BotStartupGate;
import com.idea2strategy.trading.strategy.runtime.control.StrategyBotControlException;
import com.idea2strategy.trading.strategy.runtime.warmup.WarmupRequest;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class StrategyBotControlConsumerConfigurationTest {

    @Test
    void keepsStopCommandConsumptionWiredWhenNoWarmupBundleIsConfigured() {
        var provider = new StaticListableBeanFactory().getBeanProvider(BotStartupGate.class);
        BotStartupGate gate = StrategyBotControlConsumerConfiguration.startupGate(provider);
        WarmupRequest request = new WarmupRequest(
                UUID.randomUUID(), UUID.randomUUID(), Instant.parse("2026-08-29T00:00:00Z"), Set.of());

        assertThatThrownBy(() -> gate.start(request, ignored -> {}))
                .isInstanceOfSatisfying(StrategyBotControlException.class,
                        failure -> assertThat(failure.failure()).isEqualTo(BotControlFailure.EVALUATION_BLOCKED))
                .hasMessageContaining("warm-up materialization");
    }
}
