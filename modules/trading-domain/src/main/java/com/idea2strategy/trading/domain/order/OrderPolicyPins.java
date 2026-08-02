package com.idea2strategy.trading.domain.order;

import java.util.Objects;
import java.util.UUID;

/**
 * The platform rules an accepted order is fixed against for the rest of its life.
 *
 * <p>These are pinned rather than looked up later. A fill months from now must price and charge by
 * the rules that were in force when the order was accepted, so the canonical row stores the exact
 * fee policy version and rule versions instead of a rate that could drift.
 *
 * <p>The slippage rate is not a choice: canonical {@code order_fixed_slippage_five_bps} pins it at
 * five basis points with a CHECK, which is the 0.05% the product fixes.
 */
public record OrderPolicyPins(
        UUID feePolicyId,
        String brokerRulesVersion,
        String precisionRulesVersion,
        String compositionRulesVersion) {

    /** Canonical {@code order_fixed_slippage_five_bps}. */
    public static final int FIXED_SLIPPAGE_RATE_BPS = 5;

    private static final int MAX_RULES_VERSION_LENGTH = 80;
    private static final int MAX_COMPOSITION_RULES_VERSION_LENGTH = 40;

    public OrderPolicyPins {
        Objects.requireNonNull(feePolicyId, "feePolicyId");
        requireVersion(brokerRulesVersion, "brokerRulesVersion", MAX_RULES_VERSION_LENGTH);
        requireVersion(precisionRulesVersion, "precisionRulesVersion", MAX_RULES_VERSION_LENGTH);
        requireVersion(
                compositionRulesVersion, "compositionRulesVersion", MAX_COMPOSITION_RULES_VERSION_LENGTH);
    }

    public int slippageRateBps() {
        return FIXED_SLIPPAGE_RATE_BPS;
    }

    private static void requireVersion(String value, String name, int maxLength) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " must be 1 to " + maxLength + " characters");
        }
    }
}
