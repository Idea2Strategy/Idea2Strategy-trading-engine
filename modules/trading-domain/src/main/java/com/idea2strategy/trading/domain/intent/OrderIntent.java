package com.idea2strategy.trading.domain.intent;

import java.util.UUID;

/**
 * One canonical order intent: a decided candidate plus the identity it will keep forever.
 *
 * <p>The decided fields stay in {@link OrderIntentRequest} rather than being copied out, so there is
 * exactly one place that enforces the canonical CHECKs. {@code intentKey} is the canonical
 * per-batch uniqueness handle ({@code UNIQUE (batch_id, intent_key)}); it is derived from the source
 * candidate so an at-least-once redelivery of the same evaluation lands on the same row.
 */
public record OrderIntent(UUID intentId, String intentKey, OrderIntentRequest request) {

    static final String INTENT_KEY_PREFIX = "candidate:";

    public OrderIntent {
        OrderIntentIdentityHashing.requireVersion5Rfc4122(intentId, "intentId");
        OrderIntentIdentityHashing.requireNonNull(intentKey, "intentKey");
        OrderIntentIdentityHashing.requireNonNull(request, "request");
        if (!intentKey.equals(INTENT_KEY_PREFIX + request.candidateId())) {
            throw new IllegalArgumentException("intentKey must be derived from the source candidate");
        }
    }

    public UUID candidateId() {
        return request.candidateId();
    }
}
