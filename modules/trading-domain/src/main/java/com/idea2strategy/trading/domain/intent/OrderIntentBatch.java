package com.idea2strategy.trading.domain.intent;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A finalized canonical order intent batch.
 *
 * <p>This aggregate is only ever produced whole. The canonical
 * {@code intent_batch_finalized_complete} CHECK requires a FINALIZED batch to carry both
 * {@code finalized_at} and {@code result_hash}, and the trading engine has no partial collection
 * step, so there is no representable COLLECTING state here.
 *
 * <p>There is deliberately no source candidate batch here. Canonically the cause of a batch is the
 * official {@code source_event_id}; the candidate batch identifier was the private stand-in used
 * before canonical bot events existed, and keeping both would make two records of one cause. It
 * survives as an input to {@code inputStateHash}, which is where a changed upstream batch is
 * detected.
 */
public record OrderIntentBatch(
        UUID batchId,
        UUID botId,
        UUID partitionId,
        UUID sourceEventId,
        UUID evaluationId,
        String inputStateHash,
        String conflictPolicyHash,
        String compositionRulesVersion,
        String resultHash,
        Instant finalizedAt,
        List<OrderIntent> intents) {

    private static final Pattern SHA_256_HEX = Pattern.compile("[0-9a-f]{64}");
    private static final int MAX_COMPOSITION_RULES_VERSION_LENGTH = 40;

    public OrderIntentBatch {
        OrderIntentIdentityHashing.requireVersion5Rfc4122(batchId, "batchId");
        OrderIntentIdentityHashing.requireNonNull(botId, "botId");
        OrderIntentIdentityHashing.requireNonNull(partitionId, "partitionId");
        OrderIntentIdentityHashing.requireNonNull(sourceEventId, "sourceEventId");
        OrderIntentIdentityHashing.requireNonNull(evaluationId, "evaluationId");
        OrderIntentIdentityHashing.requireNonNull(finalizedAt, "finalizedAt");
        requireSha256Hex(inputStateHash, "inputStateHash");
        requireSha256Hex(conflictPolicyHash, "conflictPolicyHash");
        requireSha256Hex(resultHash, "resultHash");

        OrderIntentIdentityHashing.requireNonNull(compositionRulesVersion, "compositionRulesVersion");
        if (compositionRulesVersion.isBlank()
                || compositionRulesVersion.length() > MAX_COMPOSITION_RULES_VERSION_LENGTH) {
            throw new IllegalArgumentException(
                    "compositionRulesVersion must be 1 to "
                            + MAX_COMPOSITION_RULES_VERSION_LENGTH + " characters");
        }

        intents = OrderIntentIdentityHashing.immutableList(intents, "intents");
        requireDistinct(intents.stream().map(OrderIntent::candidateId).toList(), "candidateIds");
        requireDistinct(intents.stream().map(OrderIntent::intentId).toList(), "intentIds");
        requireDistinct(intents.stream().map(OrderIntent::intentKey).toList(), "intentKeys");
        for (int index = 1; index < intents.size(); index++) {
            if (intents.get(index - 1).candidateId().compareTo(intents.get(index).candidateId()) >= 0) {
                throw new IllegalArgumentException("intents must be sorted by candidateId");
            }
        }
    }

    private static void requireDistinct(List<?> values, String name) {
        if (values.size() != new HashSet<>(values).size()) {
            throw new IllegalArgumentException("intents must not contain duplicate " + name);
        }
    }

    private static void requireSha256Hex(String value, String name) {
        OrderIntentIdentityHashing.requireNonNull(value, name);
        if (!SHA_256_HEX.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 hexadecimal value");
        }
    }
}
