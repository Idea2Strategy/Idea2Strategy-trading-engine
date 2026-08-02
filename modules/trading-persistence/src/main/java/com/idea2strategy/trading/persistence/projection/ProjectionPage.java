package com.idea2strategy.trading.persistence.projection;

/** Deterministic pagination window for a canonical trading read. */
public record ProjectionPage(int limit, int offset) {

    public static final int MAX_LIMIT = 500;

    public ProjectionPage {
        if (limit <= 0 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
    }

    public static ProjectionPage first(int limit) {
        return new ProjectionPage(limit, 0);
    }
}
