package com.idea2strategy.trading.domain.stop;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

public record BotStopSettlement(
        UUID settlementId,
        UUID botId,
        StopReason reason,
        String reasonDetail,
        StopCheckpoint checkpoint,
        long version,
        Instant requestedAt,
        Instant updatedAt,
        StopStep failedStep,
        String terminalReason) {

    private static final UUID SETTLEMENT_NAMESPACE = UUID.fromString("ce3f4700-4ca7-5fe6-a3dd-01137386c5a2");

    public BotStopSettlement {
        required(settlementId, "settlementId");
        required(botId, "botId");
        required(reason, "reason");
        nonBlank(reasonDetail, "reasonDetail");
        required(checkpoint, "checkpoint");
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
        required(requestedAt, "requestedAt");
        required(updatedAt, "updatedAt");
        if (updatedAt.isBefore(requestedAt)) {
            throw new IllegalArgumentException("updatedAt must not precede requestedAt");
        }
        if (checkpoint == StopCheckpoint.SETTLEMENT_FAILED) {
            required(failedStep, "failedStep");
            nonBlank(terminalReason, "terminalReason");
        } else if (failedStep != null || terminalReason != null) {
            throw new IllegalArgumentException("only SETTLEMENT_FAILED carries failure details");
        }
        if (checkpoint == StopCheckpoint.REQUESTED && version == 1 && !updatedAt.equals(requestedAt)) {
            throw new IllegalArgumentException("initial request timestamps must match");
        }
    }

    public static BotStopSettlement request(UUID botId, StopReason reason, String detail, Instant requestedAt) {
        required(botId, "botId");
        required(reason, "reason");
        nonBlank(detail, "detail");
        required(requestedAt, "requestedAt");
        UUID id = uuid5(SETTLEMENT_NAMESPACE, botId + "|" + reason + "|" + detail + "|" + requestedAt);
        return new BotStopSettlement(id, botId, reason, detail, StopCheckpoint.REQUESTED,
                1, requestedAt, requestedAt, null, null);
    }

    public UUID operationId(StopStep step) {
        return uuid5(settlementId, required(step, "step").name());
    }

    public boolean terminal() {
        return checkpoint == StopCheckpoint.STOPPED || checkpoint == StopCheckpoint.SETTLEMENT_FAILED;
    }

    public StopStep nextStep() {
        return switch (checkpoint) {
            case REQUESTED -> StopStep.BLOCK_NEW_WORK;
            case WORK_BLOCKED -> StopStep.CANCEL_ORDERS_AND_RELEASE;
            case ORDERS_CLEANED, LIQUIDATING -> StopStep.LIQUIDATE_POSITIONS;
            case STOPPED, SETTLEMENT_FAILED -> null;
        };
    }

    public BotStopSettlement completed(StopStep step, Instant occurredAt) {
        requireMutableStep(step);
        StopCheckpoint next = switch (step) {
            case BLOCK_NEW_WORK -> StopCheckpoint.WORK_BLOCKED;
            case CANCEL_ORDERS_AND_RELEASE -> StopCheckpoint.ORDERS_CLEANED;
            case LIQUIDATE_POSITIONS -> StopCheckpoint.STOPPED;
        };
        return transition(next, occurredAt, null, null);
    }

    public BotStopSettlement incomplete(StopStep step, Instant occurredAt) {
        requireMutableStep(step);
        StopCheckpoint next = step == StopStep.LIQUIDATE_POSITIONS
                ? StopCheckpoint.LIQUIDATING
                : checkpoint;
        return transition(next, occurredAt, null, null);
    }

    public BotStopSettlement failed(StopStep step, String detail, Instant occurredAt) {
        requireMutableStep(step);
        return transition(StopCheckpoint.SETTLEMENT_FAILED, occurredAt, step, nonBlank(detail, "detail"));
    }

    private void requireMutableStep(StopStep step) {
        if (terminal()) {
            throw new IllegalStateException(checkpoint + " is terminal");
        }
        if (nextStep() != required(step, "step")) {
            throw new IllegalStateException("expected step " + nextStep() + " but received " + step);
        }
    }

    private BotStopSettlement transition(
            StopCheckpoint next, Instant occurredAt, StopStep failureStep, String failureReason) {
        Instant time = required(occurredAt, "occurredAt");
        if (time.isBefore(updatedAt)) {
            throw new IllegalArgumentException("occurredAt must not precede updatedAt");
        }
        return new BotStopSettlement(settlementId, botId, reason, reasonDetail, next,
                version + 1, requestedAt, time, failureStep, failureReason);
    }

    private static UUID uuid5(UUID namespace, String value) {
        byte[] namespaceBytes = new byte[16];
        java.nio.ByteBuffer.wrap(namespaceBytes)
                .putLong(namespace.getMostSignificantBits())
                .putLong(namespace.getLeastSignificantBits());
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-1");
            digest.update(namespaceBytes);
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            var buffer = java.nio.ByteBuffer.wrap(hash);
            return new UUID(buffer.getLong(), buffer.getLong());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 unavailable", exception);
        }
    }

    private static String nonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static <T> T required(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }
}
