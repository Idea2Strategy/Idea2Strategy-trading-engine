package com.idea2strategy.trading.application.policy;

import java.time.Instant;

/**
 * Raised when no platform policy version is in force at the requested moment.
 *
 * <p>The canonical foreign keys are NOT NULL, so a write path with no resolvable policy must stop
 * rather than fall back to a default. A silent default would pin the wrong policy id onto an
 * immutable fill.
 */
public final class MissingTradingPolicyException extends RuntimeException {

    public MissingTradingPolicyException(String policyKind, Instant at) {
        super("no " + policyKind + " policy version is effective at " + at);
    }
}
