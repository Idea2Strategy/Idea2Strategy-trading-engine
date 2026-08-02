package com.idea2strategy.trading.domain.stop;

/**
 * Mirrors the canonical {@code trading.system_close_reason} enum.
 *
 * <p>Only {@link #BOT_STOP} belongs to this concern. The other three are listed because the column
 * is a real PostgreSQL enum and a value outside it is rejected by the database rather than by this
 * service, so the vocabulary has to be visible where the write is built.
 */
public enum SystemCloseReason {
    /** A risk limit was breached on a virtual position. */
    RISK_LIMIT_BREACH,
    /** A bot stop settlement is liquidating what the bot still holds. */
    BOT_STOP,
    /** A competition ended and every participating bot is flattened. */
    COMPETITION_END,
    /** Market data integrity was blocked, so the close is recorded before any price exists. */
    DATA_INTEGRITY_BLOCK
}
