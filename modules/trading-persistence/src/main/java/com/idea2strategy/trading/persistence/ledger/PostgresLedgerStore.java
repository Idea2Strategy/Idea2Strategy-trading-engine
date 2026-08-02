package com.idea2strategy.trading.persistence.ledger;

import com.idea2strategy.trading.application.ledger.LedgerCommandConflictException;
import com.idea2strategy.trading.application.ledger.PostLedgerTransactionCommand;
import com.idea2strategy.trading.application.port.LedgerStore;
import com.idea2strategy.trading.domain.ledger.LedgerDirection;
import com.idea2strategy.trading.domain.ledger.LedgerEntry;
import com.idea2strategy.trading.domain.ledger.LedgerEntryDraft;
import com.idea2strategy.trading.domain.ledger.LedgerPostingKind;
import com.idea2strategy.trading.domain.ledger.LedgerTransaction;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes postings to the canonical {@code trading.ledger_accounts},
 * {@code trading.ledger_transactions} and {@code trading.ledger_entries}.
 *
 * <p>A posting is written whole or not at all. This repository's own balance contribution installs
 * deferred constraint triggers that refuse, at commit, a transaction that is not two sided and
 * balanced, so there is no intermediate state in which a header exists without its entries.
 *
 * <p>Idempotency is canonical rather than bookkept. The private schema recognised a redelivery from
 * a receipt table keyed by a command id; here the transaction id is derived from the posting's own
 * meaning and {@code trading.ledger_transactions.bot_event_id} is UNIQUE, so a redelivery re-derives
 * the same primary key and loses the insert race, while a second posting that claims the same bot
 * event with different content collides on that unique and is refused.
 */
@Repository
public class PostgresLedgerStore implements LedgerStore {

    /**
     * What a posting from this service is caused by.
     *
     * <p>The canonical note names FILL, FILL_ADJUSTMENT, borrow fee, corporate action and initial
     * capital as the idempotent sources it expects. This store is the fill independent posting path
     * and has none of them: the only thing that caused the posting is the official bot event. Naming
     * a source this service cannot substantiate would be worse than naming the one it can, and the
     * balance contribution deliberately leaves an unrecognised {@code source_type} alone rather than
     * rejecting it.
     */
    private static final String SOURCE_TYPE = "BOT_EVENT";

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public PostgresLedgerStore(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    @Override
    public LedgerTransaction append(PostLedgerTransactionCommand command) {
        Objects.requireNonNull(command, "command");
        return transaction.execute(status -> appendInTransaction(command));
    }

    private LedgerTransaction appendInTransaction(PostLedgerTransactionCommand command) {
        LedgerTransaction posting = command.transaction();
        validateLineage(command, posting);

        int inserted = jdbc.sql("""
                        insert into trading.ledger_transactions (
                            id, bot_id, partition_id, bot_event_id, transaction_type, transaction_key,
                            source_type, source_id, currency_code, reversal_of_transaction_id,
                            occurred_at, description_code
                        ) values (
                            :id, :botId, :partitionId, :botEventId, :transactionType, :transactionKey,
                            :sourceType, :sourceId, :currencyCode, :reversalOf,
                            :occurredAt, :descriptionCode
                        )
                        on conflict do nothing
                        """)
                .param("id", posting.transactionId())
                .param("botId", command.botId())
                .param("partitionId", command.partitionId())
                .param("botEventId", posting.sourceEventId())
                .param("transactionType", transactionType(posting.kind()))
                .param("transactionKey", transactionKey(posting))
                .param("sourceType", SOURCE_TYPE)
                .param("sourceId", posting.sourceEventId())
                .param("currencyCode", posting.currency())
                .param("reversalOf", supersededTransactionId(posting))
                .param("occurredAt", offset(posting.postedAt()))
                .param("descriptionCode", descriptionCode(posting.kind()))
                .update();

        if (inserted != 1) {
            return replay(command, posting);
        }

        for (LedgerEntry entry : posting.entries()) {
            insertEntry(command, posting, entry);
        }
        return posting;
    }

    /**
     * The header was already there. Either this is the same work arriving twice, in which case the
     * stored posting equals the desired one and is returned, or the bot event already records
     * different work and the derived id does not even resolve to a row.
     */
    private LedgerTransaction replay(PostLedgerTransactionCommand command, LedgerTransaction posting) {
        StoredHeader header = header(command.botId(), posting.transactionId()).orElseThrow(this::conflict);
        if (!Objects.equals(header.partitionId(), command.partitionId())) {
            throw conflict();
        }
        LedgerTransaction stored = toDomain(header);
        if (!stored.equals(posting)) {
            throw conflict();
        }
        return stored;
    }

    /**
     * Checks what the canonical schema cannot.
     *
     * <p>{@code reversal_of_transaction_id} is a plain nullable foreign key: nothing canonical says a
     * reversal has to negate what it names, and nothing stops a transaction being reversed twice. The
     * private schema enforced the second of those with a partial unique index. That index has no
     * canonical counterpart, so the guard below is a read and not a constraint: it catches the
     * ordinary double reversal but two concurrent ones can still both pass. Restoring a real
     * guarantee needs canonical DDL, which is behind an authority gate this change does not open.
     */
    private void validateLineage(PostLedgerTransactionCommand command, LedgerTransaction posting) {
        UUID referenced = supersededTransactionId(posting);
        if (referenced == null) {
            return;
        }
        LedgerTransaction original = load(command.botId(), referenced).orElseThrow(() ->
                new LedgerCommandConflictException("referenced ledger transaction does not exist"));
        if (posting.kind() != LedgerPostingKind.REVERSAL) {
            return;
        }
        requireExactNegation(original, posting);
        boolean alreadyReversed = jdbc.sql("""
                        select exists (
                            select 1 from trading.ledger_transactions
                            where bot_id = :botId
                              and reversal_of_transaction_id = :referenced
                              and transaction_type = :reversal
                              and id <> :self
                        )
                        """)
                .param("botId", command.botId())
                .param("referenced", referenced)
                .param("reversal", transactionType(LedgerPostingKind.REVERSAL))
                .param("self", posting.transactionId())
                .query(Boolean.class)
                .single();
        if (alreadyReversed) {
            throw new LedgerCommandConflictException("referenced ledger transaction is already reversed");
        }
    }

    private void requireExactNegation(LedgerTransaction original, LedgerTransaction reversal) {
        if (reversal.entries().size() != original.entries().size()) {
            throw conflict();
        }
        for (int index = 0; index < original.entries().size(); index++) {
            LedgerEntry expected = original.entries().get(index);
            LedgerEntry actual = reversal.entries().get(index);
            if (!expected.accountCode().equals(actual.accountCode())
                    || expected.direction().opposite() != actual.direction()
                    || !expected.currency().equals(actual.currency())
                    || expected.amount().compareTo(actual.amount()) != 0) {
                throw new LedgerCommandConflictException("reversal must exactly negate the referenced transaction");
            }
        }
    }

    private void insertEntry(PostLedgerTransactionCommand command, LedgerTransaction posting, LedgerEntry entry) {
        String accountKey = CanonicalLedgerIdentity.accountKey(
                command.partitionId(), entry.accountCode(), entry.currency());
        UUID accountId = ledgerAccount(command, accountKey, entry, posting.postedAt());
        int inserted = jdbc.sql("""
                        insert into trading.ledger_entries (
                            id, bot_id, partition_id, transaction_id, ledger_account_id,
                            entry_sequence, direction, amount, entry_hash
                        ) values (
                            :id, :botId, :partitionId, :transactionId, :ledgerAccountId,
                            :sequence, cast(:direction as trading.ledger_direction), :amount, :entryHash
                        )
                        """)
                .param("id", entry.entryId())
                .param("botId", command.botId())
                .param("partitionId", command.partitionId())
                .param("transactionId", posting.transactionId())
                .param("ledgerAccountId", accountId)
                .param("sequence", entry.sequence())
                .param("direction", entry.direction().name())
                .param("amount", canonicalAmount(entry))
                .param("entryHash", CanonicalLedgerIdentity.entryHash(
                        posting.transactionId(), entry.sequence(), accountKey, entry.direction().name(),
                        entry.currency(), canonicalAmount(entry)))
                .update();
        if (inserted != 1) {
            throw conflict();
        }
    }

    /**
     * The account this entry books against, created on first use.
     *
     * <p>The canonical ledger has an account dimension the posting does not: an entry names a row in
     * {@code trading.ledger_accounts}, not a code. The account is the same fact every time, so it is
     * derived from the key and opened rather than registered, and its {@code created_at} is the
     * moment of the posting that first needed it.
     */
    private UUID ledgerAccount(
            PostLedgerTransactionCommand command, String accountKey, LedgerEntry entry, Instant openedAt) {
        jdbc.sql("""
                        insert into trading.ledger_accounts (
                            id, bot_id, account_key, partition_id, account_type, currency_code, created_at
                        ) values (
                            :id, :botId, :accountKey, :partitionId, :accountType, :currencyCode, :createdAt
                        )
                        on conflict do nothing
                        """)
                .param("id", CanonicalLedgerIdentity.accountId(command.botId(), accountKey))
                .param("botId", command.botId())
                .param("accountKey", accountKey)
                .param("partitionId", command.partitionId())
                .param("accountType", entry.accountCode())
                .param("currencyCode", entry.currency())
                .param("createdAt", offset(openedAt))
                .update();
        return jdbc.sql("""
                        select id from trading.ledger_accounts
                        where bot_id = :botId and account_key = :accountKey
                        """)
                .param("botId", command.botId())
                .param("accountKey", accountKey)
                .query(UUID.class)
                .single();
    }

    /** The stored posting, rebuilt so the caller can compare it to the one it wanted to write. */
    Optional<LedgerTransaction> load(UUID botId, UUID transactionId) {
        return header(botId, transactionId).map(this::toDomain);
    }

    private Optional<StoredHeader> header(UUID botId, UUID transactionId) {
        return jdbc.sql("""
                        select id, partition_id, bot_event_id, transaction_type,
                               reversal_of_transaction_id, occurred_at
                        from trading.ledger_transactions
                        where bot_id = :botId and id = :id
                        """)
                .param("botId", botId)
                .param("id", transactionId)
                .query((resultSet, rowNumber) -> new StoredHeader(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("partition_id", UUID.class),
                        resultSet.getObject("bot_event_id", UUID.class),
                        postingKind(resultSet.getString("transaction_type")),
                        resultSet.getObject("reversal_of_transaction_id", UUID.class),
                        resultSet.getObject("occurred_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    /**
     * A stored header without the entries that make it a posting is not a partial result, it is a
     * broken one, and the aggregate says so by refusing to be rebuilt. That refusal is a conflict
     * here rather than an argument error, because the caller supplied nothing wrong.
     */
    private LedgerTransaction toDomain(StoredHeader header) {
        try {
            return restore(header);
        } catch (IllegalArgumentException storedStateIsNotAPosting) {
            throw conflict(storedStateIsNotAPosting);
        }
    }

    private LedgerTransaction restore(StoredHeader header) {
        List<LedgerEntry> entries = jdbc.sql("""
                        select entry.id, entry.entry_sequence, entry.direction, entry.amount,
                               account.account_type, account.currency_code
                        from trading.ledger_entries entry
                        join trading.ledger_accounts account on account.id = entry.ledger_account_id
                        where entry.transaction_id = :transactionId
                        order by entry.entry_sequence
                        """)
                .param("transactionId", header.transactionId())
                .query((resultSet, rowNumber) -> new LedgerEntry(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getInt("entry_sequence"),
                        resultSet.getString("account_type"),
                        LedgerDirection.valueOf(resultSet.getString("direction")),
                        resultSet.getString("currency_code").trim(),
                        resultSet.getBigDecimal("amount"),
                        header.botEventId()))
                .list();
        boolean reversal = header.kind() == LedgerPostingKind.REVERSAL;
        return LedgerTransaction.restore(
                header.transactionId(),
                header.botEventId(),
                header.occurredAt(),
                header.kind(),
                reversal ? header.supersedes() : null,
                reversal ? null : header.supersedes(),
                entries);
    }

    /**
     * The single lineage column.
     *
     * <p>The canonical header has one {@code reversal_of_transaction_id} and no correction column,
     * while a posting distinguishes the two kinds. Both therefore land in that column and
     * {@code transaction_type} is what tells them apart, which is exactly how the pair round trips
     * without adding canonical DDL.
     */
    private static UUID supersededTransactionId(LedgerTransaction posting) {
        return posting.kind() == LedgerPostingKind.REVERSAL
                ? posting.reversesTransactionId()
                : posting.correctsTransactionId();
    }

    /**
     * The canonical natural key of the posting. {@code source_type} is the bot event, so the event is
     * also what makes the key unique inside the bot.
     */
    private static String transactionKey(LedgerTransaction posting) {
        return SOURCE_TYPE + ":" + posting.sourceEventId();
    }

    private static String transactionType(LedgerPostingKind kind) {
        return switch (kind) {
            case STANDARD -> "LEDGER_POSTING";
            case REVERSAL -> "LEDGER_REVERSAL";
            case CORRECTION -> "LEDGER_CORRECTION";
        };
    }

    private static LedgerPostingKind postingKind(String transactionType) {
        return switch (transactionType) {
            case "LEDGER_POSTING" -> LedgerPostingKind.STANDARD;
            case "LEDGER_REVERSAL" -> LedgerPostingKind.REVERSAL;
            case "LEDGER_CORRECTION" -> LedgerPostingKind.CORRECTION;
            default -> throw new LedgerCommandConflictException(
                    "ledger transaction " + transactionType + " was not written by this service");
        };
    }

    /**
     * {@code description_code} is NOT NULL and a posting carries no free text, so it restates the
     * one fact there is in the past tense the column is written in elsewhere.
     */
    private static String descriptionCode(LedgerPostingKind kind) {
        return switch (kind) {
            case STANDARD -> "LEDGER_TRANSACTION_POSTED";
            case REVERSAL -> "LEDGER_TRANSACTION_REVERSED";
            case CORRECTION -> "LEDGER_TRANSACTION_CORRECTED";
        };
    }

    /**
     * PostgreSQL always returns {@code numeric(24,8)} at scale 8, so the value is pinned to that
     * scale on the way in too. {@link LedgerEntryDraft} has already refused anything that would need
     * rounding to get there.
     */
    private static BigDecimal canonicalAmount(LedgerEntry entry) {
        return entry.amount().setScale(LedgerEntryDraft.AMOUNT_SCALE, RoundingMode.UNNECESSARY);
    }

    private static OffsetDateTime offset(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private LedgerCommandConflictException conflict() {
        return new LedgerCommandConflictException("ledger transaction identity conflict");
    }

    private LedgerCommandConflictException conflict(Throwable cause) {
        return new LedgerCommandConflictException("ledger transaction identity conflict", cause);
    }

    private record StoredHeader(
            UUID transactionId,
            UUID partitionId,
            UUID botEventId,
            LedgerPostingKind kind,
            UUID supersedes,
            Instant occurredAt) {}
}
