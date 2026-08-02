package com.idea2strategy.trading.domain.intent;

/**
 * What the bundle decided to do with one candidate.
 *
 * <p>These are the canonical {@code trading.intent_decision} values. Only {@code APPROVED} and
 * {@code REDUCED} reach an order: the canonical
 * {@code intent_nonexecuting_decision_has_no_final} CHECK forbids a final quantity on the other
 * three, which is what keeps a rejected or netted candidate from silently becoming an order.
 */
public enum IntentDecision {
    APPROVED,
    REJECTED,
    REDUCED,
    NETTED,
    CONFLICTED;

    /** True when this decision is allowed to carry a final quantity that becomes an order. */
    public boolean executes() {
        return this == APPROVED || this == REDUCED;
    }
}
