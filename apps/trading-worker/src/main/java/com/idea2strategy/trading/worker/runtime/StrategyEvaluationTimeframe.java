package com.idea2strategy.trading.worker.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.Set;

/** Resolves the one live candle cadence a Basic runtime can advance from a compiled plan. */
enum StrategyEvaluationTimeframe {
    THIRTY_MINUTES("closed30m"),
    ONE_HOUR("closed1h"),
    FOUR_HOURS("closed4h"),
    ONE_DAY("closed1d");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final String closedFlag;

    StrategyEvaluationTimeframe(String closedFlag) {
        this.closedFlag = closedFlag;
    }

    String closedFlag() {
        return closedFlag;
    }

    static StrategyEvaluationTimeframe fromPlan(String planPayload) {
        try {
            Set<StrategyEvaluationTimeframe> resolved = new LinkedHashSet<>();
            collect(MAPPER.readTree(planPayload), resolved);
            if (resolved.isEmpty()) {
                throw new IllegalArgumentException("compiled plan declares no supported live resolution");
            }
            if (resolved.size() != 1) {
                throw new IllegalArgumentException(
                        "one Basic bot cannot mix multiple live feature resolutions: " + resolved);
            }
            return resolved.iterator().next();
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalArgumentException("compiled plan is not valid JSON", failure);
        }
    }

    private static void collect(JsonNode node, Set<StrategyEvaluationTimeframe> result) {
        if (node.isObject()) {
            node.fields().forEachRemaining(field -> {
                if ("resolution".equals(field.getKey()) && field.getValue().isTextual()) {
                    result.add(parse(field.getValue().textValue()));
                } else {
                    collect(field.getValue(), result);
                }
            });
        } else if (node.isArray()) {
            node.forEach(value -> collect(value, result));
        }
    }

    private static StrategyEvaluationTimeframe parse(String value) {
        return switch (value.trim().toUpperCase(java.util.Locale.ROOT)) {
            // Existing locked 1m plans are deliberately migrated to the new minimum live cadence.
            case "1M", "PT1M", "30M", "PT30M" -> THIRTY_MINUTES;
            case "1H", "PT1H" -> ONE_HOUR;
            case "4H", "PT4H" -> FOUR_HOURS;
            case "1D", "P1D", "PT24H" -> ONE_DAY;
            default -> throw new IllegalArgumentException(
                    "live resolution must be one of 30m, 1h, 4h, 1d: " + value);
        };
    }
}
