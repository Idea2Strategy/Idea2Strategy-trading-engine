package com.idea2strategy.trading.domain.order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

/**
 * How much of an order came from one approved intent.
 *
 * <p>Canonical orders are composed, not copied: several intents in the same partition can net into
 * one order, and {@code trading.order_components} is what keeps the attribution so a fill can later
 * be allocated back to the flow that asked for it. A single-intent order is just the one-component
 * case.
 */
public record OrderComponent(UUID intentId, BigDecimal componentQuantity, int componentSequence) {

    /** Canonical {@code order_components.component_quantity} is {@code numeric(28,8)}. */
    private static final int CANONICAL_SCALE = 8;

    public OrderComponent {
        Objects.requireNonNull(intentId, "intentId");
        Objects.requireNonNull(componentQuantity, "componentQuantity");
        if (componentQuantity.signum() <= 0) {
            throw new IllegalArgumentException("componentQuantity must be positive");
        }
        if (componentSequence <= 0) {
            throw new IllegalArgumentException("componentSequence must be positive");
        }
        try {
            componentQuantity = componentQuantity.setScale(CANONICAL_SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException notExactlyRepresentable) {
            throw new IllegalArgumentException(
                    "componentQuantity exceeds the canonical scale of " + CANONICAL_SCALE,
                    notExactlyRepresentable);
        }
    }
}
