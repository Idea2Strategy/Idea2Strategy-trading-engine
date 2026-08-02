package com.idea2strategy.trading.application.stop;

import com.idea2strategy.trading.application.port.BotExecutionGatePort;
import com.idea2strategy.trading.application.port.BotStopSettlementStore;
import com.idea2strategy.trading.application.port.OpenOrderCleanupPort;
import com.idea2strategy.trading.application.port.PositionLiquidationPort;
import com.idea2strategy.trading.domain.stop.BotStopSettlement;
import com.idea2strategy.trading.domain.stop.StopStep;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class BotStopOrchestrator {
    private final BotStopSettlementStore store;
    private final BotExecutionGatePort executionGate;
    private final OpenOrderCleanupPort orderCleanup;
    private final PositionLiquidationPort liquidation;

    public BotStopOrchestrator(
            BotStopSettlementStore store,
            BotExecutionGatePort executionGate,
            OpenOrderCleanupPort orderCleanup,
            PositionLiquidationPort liquidation) {
        this.store = required(store, "store");
        this.executionGate = required(executionGate, "executionGate");
        this.orderCleanup = required(orderCleanup, "orderCleanup");
        this.liquidation = required(liquidation, "liquidation");
    }

    public BotStopSettlement requestStop(RequestBotStopCommand command) {
        required(command, "command");
        BotStopSettlement desired = BotStopSettlement.request(
                command.botId(), command.reason(), command.detail(), command.requestedAt());
        BotStopSettlement stored = required(store.createOrLoad(desired), "stored settlement");
        return runUntilBoundary(stored, command.requestedAt());
    }

    public BotStopSettlement resume(UUID settlementId, Instant occurredAt) {
        return runUntilBoundary(required(store.load(required(settlementId, "settlementId")), "settlement"),
                required(occurredAt, "occurredAt"));
    }

    public List<BotStopSettlement> resumeRecoverable(Instant occurredAt) {
        Instant time = required(occurredAt, "occurredAt");
        return required(store.loadRecoverable(), "recoverable settlements").stream()
                .map(settlement -> runUntilBoundary(settlement, time))
                .toList();
    }

    private BotStopSettlement runUntilBoundary(BotStopSettlement initial, Instant occurredAt) {
        BotStopSettlement current = initial;
        while (!current.terminal()) {
            StopStep step = current.nextStep();
            StopStepResult result = invoke(current, step);
            current = required(store.recordStep(current, step, result, occurredAt), "recorded settlement");
            if (result.status() != StopStepResultStatus.COMPLETED) {
                return current;
            }
        }
        return current;
    }

    private StopStepResult invoke(BotStopSettlement settlement, StopStep step) {
        UUID operationId = settlement.operationId(step);
        StopStepResult result = switch (step) {
            case BLOCK_NEW_WORK -> executionGate.blockNewEvaluationAndOrders(settlement.botId(), operationId);
            case CANCEL_ORDERS_AND_RELEASE -> orderCleanup.cancelOpenOrdersAndReleaseReservations(
                    settlement.botId(), operationId);
            case LIQUIDATE_POSITIONS -> liquidation.submitRemainingPositionLiquidations(
                    settlement.botId(), operationId);
        };
        return required(result, "port result");
    }

    private static <T> T required(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }
}
