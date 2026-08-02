package com.idea2strategy.trading.application.ledger;

import com.idea2strategy.trading.domain.ledger.LedgerTransaction;
import java.util.Objects;
import java.util.UUID;

/**
 * A posting together with the canonical scope that owns it.
 *
 * <p>{@code trading.ledger_transactions.bot_id} is NOT NULL under a foreign key into
 * {@code bot.bots}, and the posting itself cannot supply it: the same debit and credit mean the
 * same thing whoever books them. So ownership travels with the command rather than with the
 * accounting meaning.
 *
 * <p>{@code partitionId} is optional because the canonical model makes it optional, and only for
 * bot wide events such as initial capital. Supplying it whenever the posting belongs to one
 * partition is worth doing: the canonical entry to transaction and entry to account foreign keys
 * are composite over {@code partition_id}, and PostgreSQL treats a composite foreign key
 * containing a NULL as satisfied, so a bot wide posting is the one shape where those two keys
 * check nothing.
 *
 * <p>There is deliberately no command id. It keyed a private receipt table whose whole job was to
 * recognise a redelivery, and the canonical schema has no counterpart. Redelivery now converges on
 * the transaction id the posting derives from its own meaning, losing the insert race against the
 * canonical unique on {@code bot_event_id}.
 */
public record PostLedgerTransactionCommand(UUID botId, UUID partitionId, LedgerTransaction transaction) {

    public PostLedgerTransactionCommand {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(transaction, "transaction");
    }

    /** A posting that belongs to the bot as a whole rather than to one of its partitions. */
    public static PostLedgerTransactionCommand botWide(UUID botId, LedgerTransaction transaction) {
        return new PostLedgerTransactionCommand(botId, null, transaction);
    }
}
