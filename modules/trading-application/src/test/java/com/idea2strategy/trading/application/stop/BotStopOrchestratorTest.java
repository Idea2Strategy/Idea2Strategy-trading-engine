package com.idea2strategy.trading.application.stop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.idea2strategy.trading.application.port.BotExecutionGatePort;
import com.idea2strategy.trading.application.port.BotStopSettlementStore;
import com.idea2strategy.trading.application.port.OpenOrderCleanupPort;
import com.idea2strategy.trading.application.port.PositionLiquidationPort;
import com.idea2strategy.trading.domain.stop.BotStopSettlement;
import com.idea2strategy.trading.domain.stop.StopCheckpoint;
import com.idea2strategy.trading.domain.stop.StopReason;
import com.idea2strategy.trading.domain.stop.StopStep;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BotStopOrchestratorTest {
    private static final UUID BOT_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final Instant T0 = Instant.parse("2026-08-02T02:00:00Z");

    @Test
    void blocksWorkThenCleansOrdersThenLiquidatesAndRecoversAfterRestart() {
        InMemoryStore store = new InMemoryStore();
        ScriptedPort gate = new ScriptedPort(StopStepResult.completed("blocked"));
        ScriptedPort cleanup = new ScriptedPort(StopStepResult.completed("orders cancelled and reservations released"));
        ScriptedPort liquidation = new ScriptedPort(
                StopStepResult.partial("one position remains"),
                StopStepResult.completed("flat"));
        BotStopOrchestrator first = orchestrator(store, gate, cleanup, liquidation);

        BotStopSettlement pending = first.requestStop(new RequestBotStopCommand(
                BOT_ID, StopReason.USER_REQUEST, "user requested stop", T0));
        assertEquals(StopCheckpoint.LIQUIDATING, pending.checkpoint());

        BotStopOrchestrator restarted = orchestrator(store, gate, cleanup, liquidation);
        BotStopSettlement stopped = restarted.resumeRecoverable(T0.plusSeconds(10)).getFirst();

        assertEquals(StopCheckpoint.STOPPED, stopped.checkpoint());
        assertEquals(1, gate.calls);
        assertEquals(1, cleanup.calls);
        assertEquals(2, liquidation.calls);
    }

    @Test
    void retryableFailureLeavesDurableCheckpointAndTerminalFailureIsExplicit() {
        InMemoryStore store = new InMemoryStore();
        ScriptedPort gate = new ScriptedPort(
                StopStepResult.retryable("temporary"),
                StopStepResult.terminalFailure("manual intervention required"));
        BotStopOrchestrator service = orchestrator(
                store, gate, new ScriptedPort(), new ScriptedPort());

        BotStopSettlement retry = service.requestStop(new RequestBotStopCommand(
                BOT_ID, StopReason.ACCOUNT_SUSPENDED, "suspended", T0));
        assertEquals(StopCheckpoint.REQUESTED, retry.checkpoint());

        BotStopSettlement failed = service.resume(retry.settlementId(), T0.plusSeconds(1));
        assertEquals(StopCheckpoint.SETTLEMENT_FAILED, failed.checkpoint());
    }

    private static BotStopOrchestrator orchestrator(
            InMemoryStore store, ScriptedPort gate, ScriptedPort cleanup, ScriptedPort liquidation) {
        BotExecutionGatePort gatePort = gate::invoke;
        OpenOrderCleanupPort cleanupPort = cleanup::invoke;
        PositionLiquidationPort liquidationPort = liquidation::invoke;
        return new BotStopOrchestrator(store, gatePort, cleanupPort, liquidationPort);
    }

    private static final class ScriptedPort {
        private final Queue<StopStepResult> results = new ArrayDeque<>();
        private int calls;

        private ScriptedPort(StopStepResult... results) {
            this.results.addAll(java.util.List.of(results));
        }

        private StopStepResult invoke(UUID botId, UUID operationId) {
            calls++;
            return results.isEmpty() ? StopStepResult.completed("done") : results.remove();
        }
    }

    private static final class InMemoryStore implements BotStopSettlementStore {
        private final Map<UUID, BotStopSettlement> values = new HashMap<>();

        @Override
        public BotStopSettlement createOrLoad(BotStopSettlement desired) {
            return values.computeIfAbsent(desired.settlementId(), ignored -> desired);
        }

        @Override
        public BotStopSettlement load(UUID settlementId) {
            return values.get(settlementId);
        }

        @Override
        public List<BotStopSettlement> loadRecoverable() {
            return values.values().stream().filter(value -> !value.terminal()).toList();
        }

        @Override
        public BotStopSettlement recordStep(
                BotStopSettlement current, StopStep step, StopStepResult result, Instant occurredAt) {
            BotStopSettlement next = switch (result.status()) {
                case COMPLETED -> current.completed(step, occurredAt);
                case PARTIAL, RETRYABLE -> current.incomplete(step, occurredAt);
                case TERMINAL_FAILURE -> current.failed(step, result.detail(), occurredAt);
            };
            values.put(next.settlementId(), next);
            return next;
        }
    }
}
