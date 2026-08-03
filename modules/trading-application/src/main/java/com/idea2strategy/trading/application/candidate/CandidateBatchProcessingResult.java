package com.idea2strategy.trading.application.candidate;

public enum CandidateBatchProcessingResult {
    PROCESSED,
    DUPLICATE,
    /** The bot's stop settlement is in flight, so its new candidates are refused at intake. */
    BLOCKED_BY_STOP
}
