package com.idea2strategy.trading.worker.control;

import com.idea2strategy.trading.application.stop.BotStopOrchestrator;
import com.idea2strategy.trading.strategy.runtime.control.BotControlFailure;
import com.idea2strategy.trading.strategy.runtime.control.BotStartupGate;
import com.idea2strategy.trading.strategy.runtime.control.StrategyBotControlException;
import com.idea2strategy.trading.strategy.runtime.control.StrategyBotContractCodec;
import com.idea2strategy.trading.strategy.runtime.control.StrategyBotControlConsumer;
import com.idea2strategy.trading.strategy.runtime.control.StrategyBotExecutionPlanAdapter;
import com.idea2strategy.trading.strategy.runtime.control.StrategyBotSnapshotSource;
import com.idea2strategy.trading.strategy.runtime.plan.BasicPlanInterpreter;
import com.idea2strategy.trading.strategy.runtime.plan.ExecutionPlanCompatibility;
import com.idea2strategy.trading.worker.runtime.EvaluatingBotRuntime;
import com.idea2strategy.trading.worker.stop.BotStopSettlementConfiguration;
import com.idea2strategy.trading.worker.warmup.WarmupConfiguration;
import java.time.Clock;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * B91: wires B's bot control commands to the real evaluation and settlement path.
 *
 * <p>Every piece of this existed and none of it was connected. The consumer verified commands against
 * a locked snapshot, the startup gate prepared a warm-up, the evaluation loop could run a bot and the
 * stop orchestrator could settle one — but no {@link StrategyBotControlConsumer} bean existed, so the
 * RT5 poller that feeds it (gated on exactly this bean) never wired, and a start or stop command sat
 * in the outbox unread. This is the bean that closes the circuit.
 *
 * <p>The lifecycle handed to the consumer is the F91 bridge wrapping the evaluation loop, in that
 * order: a stop becomes a durable settlement first and halts the local runtime second, so a process
 * that dies mid-stop is resumed by the recovery worker rather than losing the stop with the process.
 *
 * <p>Ordering, duplication and reordering are the consumer's own contract and are not reimplemented
 * here: it keys a checkpoint by idempotency key, treats a stop as absorbing, and refuses a run whose
 * aggregate sequence does not move forward. What this configuration changes is that those decisions
 * now reach real evaluation and real settlement rather than a test double.
 *
 * <p>Warm-up remains fail-closed for run commands, but it cannot gate this entire consumer: stopping a
 * bot does not need market warm-up data. Without a verified bundle the fallback gate rejects only the
 * run branch while stop commands still reach durable settlement. A configured but invalid bundle still
 * fails application startup through {@link WarmupConfiguration}; it is never silently downgraded.
 *
 * <p>For the same reason {@code BotStopSettlementConfiguration} and {@code WarmupConfiguration} are
 * imported rather than left to the component scan: beans here depend on theirs, and importing makes
 * the order explicit instead of incidental.
 */
@Configuration(proxyBeanMethods = false)
@Import({BotStopSettlementConfiguration.class, WarmupConfiguration.class})
public class StrategyBotControlConsumerConfiguration {

    @Bean
    StrategyBotContractCodec strategyBotContractCodec() {
        return new StrategyBotContractCodec();
    }

    @Bean
    StrategyBotSnapshotSource strategyBotSnapshotSource(JdbcClient jdbc) {
        return new PostgresStrategyBotSnapshotSource(jdbc);
    }

    /**
     * The receipts a redelivered command is recognised by.
     *
     * <p>Declared here rather than beside the poller because the consumer needs it: gating it on the
     * consumer, as it once was, made the two conditional on each other and neither could wire.
     */
    @Bean
    OutboxReceiptBotControlCheckpointStore botControlCheckpointStore(JdbcClient jdbc) {
        return new OutboxReceiptBotControlCheckpointStore(
                jdbc, StrategyBotControlTransportConfiguration.HANDLER_ID);
    }

    /**
     * The plan versions this build can load.
     *
     * <p>The feature version map is empty because a strategy-bot plan snapshot declares no runtime
     * feature requirements to compare: the features a plan needs are named in its steps, and
     * {@link BasicPlanInterpreter} refuses a feature this build does not implement when the plan is
     * interpreted — before the bot is registered, rather than when it first evaluates.
     */
    @Bean
    ExecutionPlanCompatibility strategyBotExecutionPlanCompatibility() {
        return new ExecutionPlanCompatibility(
                BasicPlanInterpreter.PLAN_SCHEMA_VERSION,
                StrategyBotExecutionPlanAdapter.RUNTIME_SCHEMA_VERSION,
                Map.of());
    }

    /**
     * Declared by its concrete type, not as {@code BotRuntimeLifecycle}: the evaluation loop is also
     * one, and naming the interface here would leave two candidates for anything injecting it.
     */
    @Bean
    StopSettlingBotLifecycle stopSettlingBotLifecycle(
            BotStopOrchestrator orchestrator, EvaluatingBotRuntime runtime) {
        return new StopSettlingBotLifecycle(orchestrator, runtime, Clock.systemUTC());
    }

    @Bean
    StrategyBotControlConsumer strategyBotControlConsumer(
            StrategyBotContractCodec codec,
            StrategyBotSnapshotSource snapshotSource,
            OutboxReceiptBotControlCheckpointStore checkpointStore,
            StopSettlingBotLifecycle lifecycle,
            ObjectProvider<BotStartupGate> warmupGate,
            ExecutionPlanCompatibility compatibility) {
        return new StrategyBotControlConsumer(
                codec, snapshotSource, checkpointStore, lifecycle, startupGate(warmupGate), compatibility);
    }

    static BotStartupGate startupGate(ObjectProvider<BotStartupGate> provider) {
        return provider.getIfAvailable(() -> (request, starter) -> {
            throw new StrategyBotControlException(
                    BotControlFailure.EVALUATION_BLOCKED,
                    "Bot startup is unavailable until a verified warm-up materialization is configured");
        });
    }
}
