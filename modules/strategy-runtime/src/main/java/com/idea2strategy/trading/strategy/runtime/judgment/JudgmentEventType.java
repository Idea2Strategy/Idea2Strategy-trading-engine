package com.idea2strategy.trading.strategy.runtime.judgment;

public enum JudgmentEventType {
    CONDITION_SATISFIED,
    FIRST_CONDITION_FAILED,
    BUDGET_CALCULATED,
    CANDIDATE_CREATED,
    CANDIDATE_REJECTED,
    CANDIDATE_REDUCED,
    RULE_APPLIED,
    RUNTIME_STATE_CHANGED
}
