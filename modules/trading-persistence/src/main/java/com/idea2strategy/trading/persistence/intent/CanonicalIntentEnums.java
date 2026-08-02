package com.idea2strategy.trading.persistence.intent;

import com.idea2strategy.trading.domain.eligibility.OrderPositionEffect;

/**
 * Translates between the domain vocabulary and the canonical {@code trading} enum labels.
 *
 * <p>Only the position effect actually differs. The domain says increase and reduce because that is
 * what the eligibility check reasons about; the canonical model says open and close. Everything else
 * shares a name, so it is written with {@link Enum#name()} directly rather than through a table that
 * would only restate it.
 */
final class CanonicalIntentEnums {

    private CanonicalIntentEnums() {
    }

    static String positionEffect(OrderPositionEffect effect) {
        return switch (effect) {
            case INCREASE_LONG -> "OPEN_LONG";
            case REDUCE_LONG -> "CLOSE_LONG";
            case INCREASE_SHORT -> "OPEN_SHORT";
            case REDUCE_SHORT -> "CLOSE_SHORT";
        };
    }

    static OrderPositionEffect positionEffect(String canonical) {
        return switch (canonical) {
            case "OPEN_LONG" -> OrderPositionEffect.INCREASE_LONG;
            case "CLOSE_LONG" -> OrderPositionEffect.REDUCE_LONG;
            case "OPEN_SHORT" -> OrderPositionEffect.INCREASE_SHORT;
            case "CLOSE_SHORT" -> OrderPositionEffect.REDUCE_SHORT;
            default -> throw new IllegalArgumentException("unknown canonical position effect " + canonical);
        };
    }
}
