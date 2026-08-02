package com.idea2strategy.trading.domain.order;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * An accepted order as the canonical model records it.
 *
 * <p>{@link OrderLifecycle} stays the state machine and is not widened here. What the canonical row
 * needs on top of it is placement: which partition the order lives in, which intents composed it,
 * which official event accepted it, and which platform rules it is fixed against. Keeping those
 * here means the heavily proven lifecycle rules do not have to change to reach canonical storage.
 */
public record OrderPlacement(
        OrderLifecycle lifecycle,
        OrderScope scope,
        OrderPolicyPins pins,
        UUID acceptedEventId,
        List<OrderComponent> components) {

    private static final String ORDER_KEY_PREFIX = "order:";

    public OrderPlacement {
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(pins, "pins");
        Objects.requireNonNull(acceptedEventId, "acceptedEventId");
        components = List.copyOf(Objects.requireNonNull(components, "components"));
        requireComponentsCompose(components, lifecycle.terms().quantity());
    }

    /**
     * Canonical {@code UNIQUE (bot_id, partition_id, order_key)}. The order id is already derived
     * from the intent, so keying off it makes a redelivery of the same composition resolve to the
     * same row instead of a second order.
     */
    public String orderKey() {
        return ORDER_KEY_PREFIX + lifecycle.orderId();
    }

    /**
     * Digest of the contract the order is held to. Canonical indexes it so two orders claiming the
     * same terms under different rules are distinguishable, which is what makes a replayed
     * acceptance verifiable rather than merely plausible.
     */
    public String contractHash() {
        OrderTerms terms = lifecycle.terms();
        MessageDigest digest = digest();
        write(digest, "order-contract:v1");
        write(digest, terms.instrumentId().toString());
        write(digest, terms.side().name());
        write(digest, decimal(terms.quantity()));
        write(digest, terms.type().name());
        write(digest, terms.timeInForce().name());
        write(digest, decimal(terms.limitPrice()));
        write(digest, decimal(terms.stopPrice()));
        write(digest, decimal(terms.trailPercent()));
        write(digest, instant(terms.expiresAt()));
        write(digest, pins.feePolicyId().toString());
        write(digest, pins.brokerRulesVersion());
        write(digest, pins.precisionRulesVersion());
        write(digest, Integer.toString(pins.slippageRateBps()));
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * The components have to add up to the order. Canonical does not check this, because it cannot
     * see the order quantity from a component row, so an order that claims more than its intents
     * approved would otherwise be storable.
     */
    private static void requireComponentsCompose(List<OrderComponent> components, BigDecimal quantity) {
        if (components.isEmpty()) {
            throw new IllegalArgumentException("an order must be composed of at least one intent");
        }
        Set<UUID> intents = new HashSet<>();
        Set<Integer> sequences = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (OrderComponent component : components) {
            if (!intents.add(component.intentId())) {
                throw new IllegalArgumentException("an intent may contribute to an order only once");
            }
            if (!sequences.add(component.componentSequence())) {
                throw new IllegalArgumentException("component sequences must be distinct");
            }
            total = total.add(component.componentQuantity());
        }
        if (total.compareTo(quantity) != 0) {
            throw new IllegalArgumentException(
                    "components total " + total.toPlainString()
                            + " but the order is for " + quantity.toPlainString());
        }
    }

    private static String decimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static String instant(Instant value) {
        return value == null ? null : value.toString();
    }

    private static void write(MessageDigest digest, String value) {
        if (value == null) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }
}
