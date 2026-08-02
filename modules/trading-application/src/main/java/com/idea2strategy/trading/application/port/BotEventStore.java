package com.idea2strategy.trading.application.port;

import com.idea2strategy.trading.domain.event.BotEvent;
import com.idea2strategy.trading.domain.event.BotEventAppend;
import java.util.Optional;
import java.util.UUID;

/** Append-only access to {@code bot.bot_events}, this service's cause for every canonical write. */
public interface BotEventStore {

    /**
     * Appends the event, or returns the row that already recorded the same work.
     *
     * <p>Redelivery of identical work must return the existing row rather than a second one, and
     * different work presented under a key that is already taken must fail rather than overwrite
     * it.
     */
    BotEvent appendOrLoad(BotEventAppend append);

    /** Finds an event by its identifier within one bot. */
    Optional<BotEvent> find(UUID botId, UUID eventId);
}
