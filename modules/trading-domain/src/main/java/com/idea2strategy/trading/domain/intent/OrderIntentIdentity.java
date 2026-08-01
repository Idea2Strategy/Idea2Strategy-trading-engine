package com.idea2strategy.trading.domain.intent;

import java.util.UUID;

public record OrderIntentIdentity(UUID intentId, UUID candidateId) {

    public OrderIntentIdentity {
        OrderIntentIdentityHashing.requireVersion5Rfc4122(intentId, "intentId");
        OrderIntentIdentityHashing.requireNonNull(candidateId, "candidateId");
    }
}
