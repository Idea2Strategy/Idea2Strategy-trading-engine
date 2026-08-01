package com.idea2strategy.trading.strategy.runtime.judgment;

public enum JudgmentAppendFailure {
    STALE_JOURNAL_SEQUENCE,
    EVENT_IDENTITY_CONFLICT,
    DUPLICATE_FIRST_FAILURE,
    RUNTIME_REVISION_MISMATCH,
    RUNTIME_REVISION_GAP
}
