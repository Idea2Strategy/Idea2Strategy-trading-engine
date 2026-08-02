package com.idea2strategy.trading.application.order;

import java.time.Instant;
import java.util.UUID;

public sealed interface OrderLifecycleCommand
        permits FillOrderCommand, CancelOrderCommand, ExpireOrderCommand {
    UUID commandId();

    UUID orderId();

    /**
     * The official bot event this transition is recorded under.
     *
     * <p>Canonical {@code trading.order_events.bot_event_id} is a NOT NULL unique foreign key into
     * {@code bot.bot_events}: every order transition has to name the event that caused it, and no
     * two transitions may claim the same one. A command therefore cannot be applied without one.
     */
    UUID botEventId();

    long expectedVersion();

    Instant occurredAt();
}
