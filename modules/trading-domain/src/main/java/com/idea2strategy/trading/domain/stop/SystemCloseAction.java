package com.idea2strategy.trading.domain.stop;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code trading.system_close_actions}: the platform's own reason for forcing a close.
 *
 * <p>The canonical note keeps this separate from a user flow decision, and the table proves the
 * separation structurally: {@code generated_intent_id} is a NOT NULL UNIQUE foreign key into
 * {@code trading.order_intents}, so an action cannot exist without the {@code SYSTEM_*} intent it
 * produced, and one intent can back only one action. The stop settlement therefore records
 * evidence for work the liquidation step has already turned into intents; it never invents a
 * close.
 *
 * <p>{@code source_event_id} is deliberately absent from this record. It is the
 * {@code bot.bot_events} row of the settlement step that produced the close, which only the store
 * knows, and supplying it here would let a caller attribute a close to an unrelated cause.
 */
public record SystemCloseAction(
        UUID actionId,
        UUID botId,
        UUID partitionId,
        UUID flowId,
        UUID instrumentId,
        SystemCloseReason reasonType,
        BigDecimal requestedQuantity,
        UUID generatedIntentId,
        String reasonDocument,
        String calculationHash) {

    /** {@code requested_quantity} is {@code numeric(28,8)}. */
    public static final int QUANTITY_SCALE = 8;

    /** {@code calculation_hash} is {@code varchar(128)}. */
    public static final int MAX_CALCULATION_HASH_LENGTH = 128;

    private static final UUID ACTION_NAMESPACE = UUID.fromString("6f2a1c88-3f6e-5a4b-9c17-2b0d5e8f4a31");

    public SystemCloseAction {
        Objects.requireNonNull(actionId, "actionId");
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(partitionId, "partitionId");
        Objects.requireNonNull(flowId, "flowId");
        Objects.requireNonNull(instrumentId, "instrumentId");
        Objects.requireNonNull(reasonType, "reasonType");
        Objects.requireNonNull(generatedIntentId, "generatedIntentId");
        requestedQuantity = scaled(requestedQuantity);
        reasonDocument = requireDocument(reasonDocument);
        calculationHash = requireHash(calculationHash);
    }

    /**
     * The action a bot stop settlement records for one instrument it is flattening.
     *
     * <p>The identifier is derived from the generated intent because that column is already unique
     * in canonical storage. A redelivered settlement step recomputes the same intent and therefore
     * the same action row rather than a duplicate.
     */
    public static SystemCloseAction forBotStop(
            UUID botId,
            UUID partitionId,
            UUID flowId,
            UUID instrumentId,
            BigDecimal requestedQuantity,
            UUID generatedIntentId,
            String reasonDocument,
            String calculationHash) {
        UUID actionId = StopIdentity.uuid5(
                ACTION_NAMESPACE, Objects.requireNonNull(generatedIntentId, "generatedIntentId").toString());
        return new SystemCloseAction(
                actionId, botId, partitionId, flowId, instrumentId, SystemCloseReason.BOT_STOP,
                requestedQuantity, generatedIntentId, reasonDocument, calculationHash);
    }

    /**
     * The canonical column keeps eight decimal places and {@code record} equality compares scale,
     * so the scale is fixed here rather than wherever the value happens to be read back.
     * {@code UNNECESSARY} refuses a value that would silently lose precision.
     */
    private static BigDecimal scaled(BigDecimal value) {
        BigDecimal quantity = Objects.requireNonNull(value, "requestedQuantity")
                .setScale(QUANTITY_SCALE, RoundingMode.UNNECESSARY);
        if (quantity.signum() <= 0) {
            throw new IllegalArgumentException("requestedQuantity must be positive");
        }
        return quantity;
    }

    private static String requireDocument(String value) {
        String normalized = Objects.requireNonNull(value, "reasonDocument").strip();
        if (!normalized.startsWith("{") || !normalized.endsWith("}")) {
            throw new IllegalArgumentException("reasonDocument must be a JSON object");
        }
        return normalized;
    }

    private static String requireHash(String value) {
        String normalized = Objects.requireNonNull(value, "calculationHash").strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("calculationHash must not be blank");
        }
        if (normalized.length() > MAX_CALCULATION_HASH_LENGTH) {
            throw new IllegalArgumentException(
                    "calculationHash must not exceed " + MAX_CALCULATION_HASH_LENGTH + " characters");
        }
        return normalized;
    }
}
