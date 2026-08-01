package com.idea2strategy.trading.domain.intent;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

public record OrderIntentBatch(
        UUID batchId,
        UUID botId,
        UUID evaluationId,
        UUID sourceCandidateBatchId,
        String requestFingerprint,
        List<OrderIntentIdentity> intents) {

    private static final Pattern REQUEST_FINGERPRINT = Pattern.compile("[0-9a-f]{64}");

    public OrderIntentBatch {
        OrderIntentIdentityHashing.requireVersion5Rfc4122(batchId, "batchId");
        OrderIntentIdentityHashing.requireNonNull(botId, "botId");
        OrderIntentIdentityHashing.requireNonNull(evaluationId, "evaluationId");
        OrderIntentIdentityHashing.requireNonNull(sourceCandidateBatchId, "sourceCandidateBatchId");
        OrderIntentIdentityHashing.requireNonNull(requestFingerprint, "requestFingerprint");
        if (!REQUEST_FINGERPRINT.matcher(requestFingerprint).matches()) {
            throw new IllegalArgumentException("requestFingerprint must be a lowercase SHA-256 hexadecimal value");
        }
        intents = OrderIntentIdentityHashing.immutableList(intents, "intents");
        if (intents.size() != new HashSet<>(intents.stream().map(OrderIntentIdentity::candidateId).toList()).size()) {
            throw new IllegalArgumentException("intents must not contain duplicate candidateIds");
        }
        if (intents.size() != new HashSet<>(intents.stream().map(OrderIntentIdentity::intentId).toList()).size()) {
            throw new IllegalArgumentException("intents must not contain duplicate intentIds");
        }
        for (int index = 1; index < intents.size(); index++) {
            if (intents.get(index - 1).candidateId().compareTo(intents.get(index).candidateId()) >= 0) {
                throw new IllegalArgumentException("intents must be sorted by candidateId");
            }
        }
    }
}
