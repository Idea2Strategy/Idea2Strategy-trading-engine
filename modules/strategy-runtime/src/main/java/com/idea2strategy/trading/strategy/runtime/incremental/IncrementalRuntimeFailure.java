package com.idea2strategy.trading.strategy.runtime.incremental;

public enum IncrementalRuntimeFailure {
    BOT_ID_MISMATCH,
    TRIGGER_SEQUENCE_DUPLICATE_OR_STALE,
    TRIGGER_SEQUENCE_GAP,
    FEATURE_CALCULATION_FAILED
}
