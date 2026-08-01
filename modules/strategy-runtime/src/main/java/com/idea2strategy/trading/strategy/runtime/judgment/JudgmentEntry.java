package com.idea2strategy.trading.strategy.runtime.judgment;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record JudgmentEntry(
        UUID eventId,
        long sequence,
        JudgmentEventType type,
        JudgmentSubject subject,
        Map<String, String> evidence,
        Optional<RuntimeStateTransition> runtimeStateTransition) {

    public JudgmentEntry {
        eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        type = Objects.requireNonNull(type, "type must not be null");
        subject = Objects.requireNonNull(subject, "subject must not be null");
        evidence = JudgmentValueValidation.immutableValues(evidence, "evidence");
        runtimeStateTransition = Objects.requireNonNull(
                runtimeStateTransition, "runtimeStateTransition must not be null");
    }

    static JudgmentEntry from(long sequence, JudgmentEventDraft draft) {
        return new JudgmentEntry(
                draft.eventId(),
                sequence,
                draft.type(),
                draft.subject(),
                draft.evidence(),
                draft.runtimeStateTransition());
    }

    boolean hasSameMeaning(JudgmentEventDraft draft) {
        return eventId.equals(draft.eventId())
                && type == draft.type()
                && subject.equals(draft.subject())
                && evidence.equals(draft.evidence())
                && runtimeStateTransition.equals(draft.runtimeStateTransition());
    }
}
