package com.idea2strategy.trading.strategy.runtime.judgment;

import java.util.Map;

public record RuntimeStateTransition(
        long fromRevision,
        long toRevision,
        Map<String, String> replacementValues) {

    public RuntimeStateTransition {
        if (fromRevision < 0 || toRevision < 0) {
            throw new IllegalArgumentException("runtime state revisions must not be negative");
        }
        replacementValues = JudgmentValueValidation.immutableValues(
                replacementValues, "replacementValues");
    }
}
