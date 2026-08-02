package com.idea2strategy.trading.domain.intent;

/**
 * Why an intent exists.
 *
 * <p>These are the canonical {@code trading.intent_origin_type} values. The distinction is not
 * cosmetic: {@code FLOW_EVALUATION} is the only origin that carries an evaluation run, because the
 * canonical {@code flow_intent_requires_evaluation} and {@code system_intent_has_no_evaluation}
 * CHECKs make the two mutually exclusive. A system liquidation is produced from an official event
 * with no evaluation behind it.
 */
public enum OrderIntentOrigin {
    FLOW_EVALUATION,
    SYSTEM_STOP_LIQUIDATION,
    SYSTEM_FORCED_BUY_IN,
    CORPORATE_ACTION;

    /** True when the canonical row must name the evaluation run that produced it. */
    public boolean requiresEvaluationRun() {
        return this == FLOW_EVALUATION;
    }
}
