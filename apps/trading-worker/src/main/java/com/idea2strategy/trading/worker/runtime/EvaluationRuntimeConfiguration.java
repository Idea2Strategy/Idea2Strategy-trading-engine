package com.idea2strategy.trading.worker.runtime;

import com.idea2strategy.trading.application.candidate.CandidateBatchProcessor;
import com.idea2strategy.trading.application.port.BotEventStore;
import com.idea2strategy.trading.strategy.runtime.control.BotRuntimeLifecycle;
import com.idea2strategy.trading.worker.candidate.OrderCandidateBatchAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Wires the evaluation loop as the worker's production {@link BotRuntimeLifecycle}.
 *
 * <p>Declared as {@link EvaluatingBotRuntime} rather than as the interface so the market-event
 * transport can inject it by its own type and call {@code feed}, which is not part of the lifecycle
 * contract. The F91 bridge that wraps a lifecycle to make stops durable takes this as its delegate,
 * so the ordering there is unchanged: a stop still settles whatever the delegate does with it.
 */
@Configuration(proxyBeanMethods = false)
public class EvaluationRuntimeConfiguration {

    @Bean
    PostgresBotScopeResolver botScopeResolver(JdbcClient jdbc) {
        return new PostgresBotScopeResolver(jdbc);
    }

    @Bean
    PostgresEvaluationRunRecorder evaluationRunRecorder(JdbcClient jdbc, BotEventStore events) {
        return new PostgresEvaluationRunRecorder(jdbc, events);
    }

    @Bean
    PostgresPositionMetricSource positionMetricSource(JdbcClient jdbc) {
        return new PostgresPositionMetricSource(jdbc);
    }

    @Bean
    PostgresExecutionGateStateSource executionGateStateSource(JdbcClient jdbc) {
        return new PostgresExecutionGateStateSource(jdbc);
    }

    @Bean
    EvaluatingBotRuntime evaluatingBotRuntime(
            CandidateBatchProcessor processor,
            OrderCandidateBatchAdapter adapter,
            PostgresBotScopeResolver scopeResolver,
            PostgresEvaluationRunRecorder runRecorder,
            PostgresPositionMetricSource positionMetricSource,
            PostgresExecutionGateStateSource executionGateStateSource) {
        return new EvaluatingBotRuntime(
                processor, adapter, scopeResolver, runRecorder,
                positionMetricSource, executionGateStateSource);
    }
}
