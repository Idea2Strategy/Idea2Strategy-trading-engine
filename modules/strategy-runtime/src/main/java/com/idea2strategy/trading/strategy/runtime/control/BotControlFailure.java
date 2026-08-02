package com.idea2strategy.trading.strategy.runtime.control;

public enum BotControlFailure {
    UNSUPPORTED_CONTRACT_VERSION,
    INVALID_MESSAGE,
    OUTBOX_METADATA_MISMATCH,
    PLAN_INTEGRITY_MISMATCH,
    SNAPSHOT_NOT_FOUND,
    SNAPSHOT_HASH_MISMATCH,
    EVALUATION_BLOCKED
}
