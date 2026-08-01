package com.idea2strategy.trading.market.alpaca;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

public record ProviderRightsEvidence(
        String provider,
        String feed,
        Instant verifiedAt,
        Instant expiresAt) {

    public ProviderRightsEvidence {
        provider = requireText(provider, "provider").toLowerCase(Locale.ROOT);
        feed = requireText(feed, "feed").toLowerCase(Locale.ROOT);
        verifiedAt = Objects.requireNonNull(verifiedAt, "verifiedAt");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(verifiedAt)) {
            throw new IllegalArgumentException("expiresAt must be after verifiedAt");
        }
    }

    public boolean isCurrentAt(Instant instant) {
        return !instant.isBefore(verifiedAt) && instant.isBefore(expiresAt);
    }

    private static String requireText(String value, String name) {
        String text = Objects.requireNonNull(value, name).trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return text;
    }
}
