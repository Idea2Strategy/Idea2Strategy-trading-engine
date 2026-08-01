package com.idea2strategy.trading.strategy.runtime.judgment;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record JudgmentEventDraft(
        UUID eventId,
        JudgmentEventType type,
        JudgmentSubject subject,
        Map<String, String> evidence,
        Optional<RuntimeStateTransition> runtimeStateTransition) {

    public JudgmentEventDraft {
        eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        type = Objects.requireNonNull(type, "type must not be null");
        subject = Objects.requireNonNull(subject, "subject must not be null");
        evidence = JudgmentValueValidation.immutableValues(evidence, "evidence");
        runtimeStateTransition = Objects.requireNonNull(
                runtimeStateTransition, "runtimeStateTransition must not be null");
        if ((type == JudgmentEventType.RUNTIME_STATE_CHANGED) != runtimeStateTransition.isPresent()) {
            throw new IllegalArgumentException(
                    "only RUNTIME_STATE_CHANGED requires a runtime state transition");
        }
        if (type == JudgmentEventType.FIRST_CONDITION_FAILED
                && (subject.flowId().isEmpty() || subject.instrumentId().isEmpty())) {
            throw new IllegalArgumentException(
                    "FIRST_CONDITION_FAILED requires flowId and instrumentId");
        }
    }
}
