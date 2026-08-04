package com.idea2strategy.trading.domain.event;

/**
 * The bot event types this service appends.
 *
 * <p>{@code bot.bot_events} is the per-bot append-only official stream. Its canonical note describes
 * the Trigger Router appending one row per routed trigger event, and separately names
 * {@code SETTLEMENT_FAILED}, {@code LEDGER_INVARIANT_VIOLATED} and {@code STATE_REBUILD_COMPLETED},
 * which are this service's concerns and cannot come from a market or schedule trigger. The write
 * ownership tables in {@code docs/backend-and-aws-architecture.md} and the enforced
 * {@code DatabaseAccessPolicy} both assign {@code bot.bot_events} to the trading engine.
 *
 * <p>Every canonical trading write is caused by one of these, because
 * {@code trading.orders.accepted_event_id}, {@code trading.order_events.bot_event_id},
 * {@code trading.fills.bot_event_id}, {@code trading.resource_reservations.created_event_id},
 * {@code trading.reservation_events.bot_event_id}, {@code trading.ledger_transactions.bot_event_id},
 * {@code trading.fill_adjustments.bot_event_id}, {@code trading.lot_movements.bot_event_id},
 * {@code trading.system_close_actions.source_event_id} and
 * {@code trading.short_borrow_fee_accruals.bot_event_id} are all NOT NULL foreign keys into it.
 *
 * <p>The column is {@code varchar(80)} with no canonical CHECK, so this enum is the service's own
 * vocabulary rather than a canonical constraint. Three of the values are quoted verbatim from the
 * canonical note; the rest name transitions this service already implements.
 */
public enum BotEventType {

    /** An order contract was accepted for execution. */
    ORDER_ACCEPTED,
    /** An order was refused before any fill. */
    ORDER_REJECTED,
    /** An individual fill was recorded against an order. */
    ORDER_FILLED,
    /** An open order was withdrawn by an automatic replacement. */
    ORDER_CANCELLED,
    /** An open order reached its time in force boundary. */
    ORDER_EXPIRED,
    /** A correction or reversal was recorded against an existing fill. */
    FILL_ADJUSTED,
    /** Cash, buying power or lot quantity was reserved for an order intent. */
    RESERVATION_CREATED,
    /** A reservation was consumed, settled or released. */
    RESERVATION_SETTLED,
    /** A double-entry transaction was posted to the official ledger. */
    LEDGER_TRANSACTION_POSTED,
    /** A room evaluation's locked initial cash was posted to the official ledger. */
    INITIAL_CAPITAL_POSTED,
    /** Borrow fee was accrued against an open short lot. */
    SHORT_BORROW_FEE_ACCRUED,
    /** The platform generated a forced close for a bot stop, risk breach or competition end. */
    SYSTEM_CLOSE_REQUESTED,

    /**
     * A bot stop settlement was requested. This is the first checkpoint of the settlement, and
     * because canonical storage has no stop settlement table the event itself carries the state.
     */
    SETTLEMENT_REQUESTED,
    /** One settlement step advanced the checkpoint without ending the settlement. */
    SETTLEMENT_STEP_RECORDED,
    /** Bot stop settlement reached its terminal success checkpoint. */
    SETTLEMENT_COMPLETED,
    /** Documented in the canonical note. Bot stop settlement could not complete. */
    SETTLEMENT_FAILED,
    /** Documented in the canonical note. The official ledger failed its own invariant. */
    LEDGER_INVARIANT_VIOLATED,
    /** Documented in the canonical note. Runtime state was rebuilt after a restart. */
    STATE_REBUILD_COMPLETED,
    /**
     * One flow finished evaluating one official trigger, whatever it decided.
     *
     * <p>C18's judgment record. It is the event a {@code bot.evaluation_runs} row is triggered by and
     * the source event a candidate batch is keyed to, so an evaluation that produced candidates is
     * anchored to the same official event as the intents it became. The column is
     * {@code varchar(80)}, so this needs no canonical change — but the store reads the column back
     * through {@code valueOf}, which is why the name has to live here rather than being spelled at a
     * call site.
     */
    EVALUATION_COMPLETED;

    /** The value stored in {@code bot.bot_events.event_type}. */
    public String storedValue() {
        return name();
    }
}
