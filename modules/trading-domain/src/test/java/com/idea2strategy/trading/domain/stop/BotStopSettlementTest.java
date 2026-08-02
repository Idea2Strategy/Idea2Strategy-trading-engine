package com.idea2strategy.trading.domain.stop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BotStopSettlementTest {
    private static final UUID BOT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final Instant T0 = Instant.parse("2026-08-02T01:00:00Z");

    @Test
    void stopIsTerminalAndAdvancesOnlyThroughSafeCheckpoints() {
        BotStopSettlement requested = BotStopSettlement.request(BOT_ID, StopReason.USER_REQUEST, "user-stop", T0);

        BotStopSettlement blocked = requested.completed(StopStep.BLOCK_NEW_WORK, T0.plusSeconds(1));
        BotStopSettlement cleaned = blocked.completed(StopStep.CANCEL_ORDERS_AND_RELEASE, T0.plusSeconds(2));
        BotStopSettlement partial = cleaned.incomplete(StopStep.LIQUIDATE_POSITIONS, T0.plusSeconds(3));
        BotStopSettlement stopped = partial.completed(StopStep.LIQUIDATE_POSITIONS, T0.plusSeconds(4));

        assertEquals(StopCheckpoint.STOPPED, stopped.checkpoint());
        assertTrue(stopped.terminal());
        assertThrows(IllegalStateException.class,
                () -> stopped.incomplete(StopStep.LIQUIDATE_POSITIONS, T0.plusSeconds(5)));
    }

    @Test
    void terminalProviderFailureCannotBeSilentlyConvertedToStopped() {
        BotStopSettlement requested = BotStopSettlement.request(
                BOT_ID, StopReason.ACCOUNT_SUSPENDED, "account-suspended", T0);
        BotStopSettlement failed = requested.failed(
                StopStep.BLOCK_NEW_WORK, "execution gate unavailable permanently", T0.plusSeconds(1));

        assertEquals(StopCheckpoint.SETTLEMENT_FAILED, failed.checkpoint());
        assertEquals(StopReason.ACCOUNT_SUSPENDED, failed.reason());
        assertThrows(IllegalStateException.class,
                () -> failed.completed(StopStep.BLOCK_NEW_WORK, T0.plusSeconds(2)));
    }

    @Test
    void retriesKeepTheSameCheckpointAndDeterministicOperationIdentity() {
        BotStopSettlement requested = BotStopSettlement.request(BOT_ID, StopReason.POLICY_FORCED, "policy", T0);
        BotStopSettlement retry = requested.incomplete(StopStep.BLOCK_NEW_WORK, T0.plusSeconds(1));

        assertEquals(StopCheckpoint.REQUESTED, retry.checkpoint());
        assertEquals(requested.operationId(StopStep.BLOCK_NEW_WORK), retry.operationId(StopStep.BLOCK_NEW_WORK));
        assertEquals(requested.settlementId(), BotStopSettlement.request(
                BOT_ID, StopReason.POLICY_FORCED, "policy", T0).settlementId());
    }
}
