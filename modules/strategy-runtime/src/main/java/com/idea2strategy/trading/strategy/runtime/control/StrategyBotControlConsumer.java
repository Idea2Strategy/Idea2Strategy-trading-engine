package com.idea2strategy.trading.strategy.runtime.control;

import com.idea2strategy.trading.strategy.runtime.plan.ExecutionPlanCompatibility;
import com.idea2strategy.trading.strategy.runtime.plan.ExecutionPlanSourceSnapshot;
import com.idea2strategy.trading.strategy.runtime.plan.LoadedExecutionPlan;
import com.idea2strategy.trading.strategy.runtime.plan.LockedExecutionPlanLoader;
import com.idea2strategy.trading.strategy.runtime.warmup.WarmupRequest;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class StrategyBotControlConsumer implements BotEvaluationGate {
    private final StrategyBotContractCodec codec;
    private final StrategyBotSnapshotSource snapshotSource;
    private final BotControlCheckpointStore checkpointStore;
    private final BotRuntimeLifecycle lifecycle;
    private final BotStartupGate warmupGate;
    private final ExecutionPlanCompatibility compatibility;
    private final StrategyBotExecutionPlanAdapter planAdapter = new StrategyBotExecutionPlanAdapter();

    public StrategyBotControlConsumer(
            StrategyBotContractCodec codec,
            StrategyBotSnapshotSource snapshotSource,
            BotControlCheckpointStore checkpointStore,
            BotRuntimeLifecycle lifecycle,
            BotStartupGate warmupGate,
            ExecutionPlanCompatibility compatibility) {
        this.codec = Objects.requireNonNull(codec, "codec");
        this.snapshotSource = Objects.requireNonNull(snapshotSource, "snapshotSource");
        this.checkpointStore = Objects.requireNonNull(checkpointStore, "checkpointStore");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.warmupGate = Objects.requireNonNull(warmupGate, "warmupGate");
        this.compatibility = Objects.requireNonNull(compatibility, "compatibility");
    }

    public synchronized BotControlResult consume(StrategyBotOutboxEnvelope envelope) {
        StrategyBotCommand command = codec.decodeCommand(envelope);
        BotControlCheckpoint current = checkpointStore.find(command.botId())
                .orElseGet(() -> BotControlCheckpoint.initial(command.botId()));
        String idempotencyKey = command.metadata().idempotencyKey();
        if (current.processed(idempotencyKey)) {
            return BotControlResult.DUPLICATE_IGNORED;
        }

        StrategyBotCompiledPlan compiledPlan = snapshotSource.findCompiledPlan(command.botId())
                .map(codec::decodeCompiledPlan)
                .orElseThrow(() -> new StrategyBotControlException(
                        BotControlFailure.SNAPSHOT_NOT_FOUND,
                        "Locked snapshot was not found for bot " + command.botId()));
        if (!command.expectedSnapshotHash().equals(compiledPlan.snapshotHash())) {
            throw new StrategyBotControlException(
                    BotControlFailure.SNAPSHOT_HASH_MISMATCH,
                    command.expectedSnapshotHash() + " != " + compiledPlan.snapshotHash());
        }

        if (command instanceof StrategyBotStopCommand stop) {
            if (current.status() == BotControlStatus.STOPPED) {
                checkpointStore.save(current.transition(
                        BotControlStatus.STOPPED,
                        compiledPlan.snapshotHash(),
                        envelope.aggregateSequence(),
                        idempotencyKey));
                return BotControlResult.OUT_OF_ORDER_IGNORED;
            }
            lifecycle.stop(stop.botId(), stop.reasonCode());
            checkpointStore.save(current.transition(
                    BotControlStatus.STOPPED,
                    compiledPlan.snapshotHash(),
                    envelope.aggregateSequence(),
                    idempotencyKey));
            return BotControlResult.STOPPED;
        }

        StrategyBotRunCommand run = (StrategyBotRunCommand) command;
        if (current.status() == BotControlStatus.STOPPED
                || current.status() == BotControlStatus.RUNNING
                || envelope.aggregateSequence() <= current.lastAggregateSequence()) {
            checkpointStore.save(current.transition(
                    current.status(),
                    compiledPlan.snapshotHash(),
                    envelope.aggregateSequence(),
                    idempotencyKey));
            return BotControlResult.OUT_OF_ORDER_IGNORED;
        }
        ExecutionPlanSourceSnapshot sourceSnapshot = planAdapter.adapt(run.botId(), compiledPlan);
        LoadedExecutionPlan loaded = new LockedExecutionPlanLoader(
                ignored -> Optional.of(sourceSnapshot), compatibility).load(run.botId());
        WarmupRequest warmupRequest = new WarmupRequest(
                run.botId(), loaded.releaseId(), run.executionEligibleFrom(), compiledPlan.warmupRequirements());
        warmupGate.start(warmupRequest,
                prepared -> lifecycle.start(loaded, prepared, run.executionEligibleFrom()));
        checkpointStore.save(current.transition(
                BotControlStatus.RUNNING,
                compiledPlan.snapshotHash(),
                envelope.aggregateSequence(),
                idempotencyKey));
        return BotControlResult.STARTED;
    }

    public boolean canAcceptEvaluation(UUID botId) {
        Objects.requireNonNull(botId, "botId");
        return checkpointStore.find(botId)
                .map(BotControlCheckpoint::status)
                .filter(status -> status == BotControlStatus.RUNNING)
                .isPresent();
    }

    @Override
    public void requireEvaluationAllowed(UUID botId) {
        if (!canAcceptEvaluation(botId)) {
            throw new BotEvaluationBlockedException(botId);
        }
    }
}
