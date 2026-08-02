package com.idea2strategy.trading.persistence.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

/** Reads the canonical ledger tables through the jOOQ query boundary. */
@Repository
public class JooqLedgerQuery {
    private final DSLContext dsl;

    public JooqLedgerQuery(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    public int countTransactions() {
        return dsl.fetchCount(dsl.selectFrom("trading.ledger_transactions"));
    }

    public int countEntries() {
        return dsl.fetchCount(dsl.selectFrom("trading.ledger_entries"));
    }

    public int countAccounts() {
        return dsl.fetchCount(dsl.selectFrom("trading.ledger_accounts"));
    }

    public Optional<LedgerTransactionPersistenceView> findTransaction(UUID transactionId) {
        Objects.requireNonNull(transactionId, "transactionId");
        return dsl.fetchOptional(header("id = ?"), transactionId).map(this::toView);
    }

    /**
     * The canonical {@code bot_event_id} is UNIQUE, so one official event has at most one posting.
     * That uniqueness is what replaced the private receipt table.
     */
    public Optional<LedgerTransactionPersistenceView> findByBotEventId(UUID botEventId) {
        Objects.requireNonNull(botEventId, "botEventId");
        return dsl.fetchOptional(header("bot_event_id = ?"), botEventId).map(this::toView);
    }

    /**
     * Debit and credit totals per account, rebuilt from the entries alone.
     *
     * <p>Grouped by the canonical {@code account_key} rather than by the account type, because the
     * key is the handle the canonical unique index uses and it already carries the scope and the
     * currency the type on its own does not.
     */
    public List<AccountTotals> accountTotals() {
        return dsl.fetch("""
                        select account.account_key,
                               account.account_type,
                               account.currency_code,
                               sum(case when entry.direction = 'DEBIT' then entry.amount else 0 end) as debits,
                               sum(case when entry.direction = 'CREDIT' then entry.amount else 0 end) as credits
                        from trading.ledger_entries entry
                        join trading.ledger_accounts account on account.id = entry.ledger_account_id
                        group by account.account_key, account.account_type, account.currency_code
                        order by account.account_key
                        """)
                .map(record -> new AccountTotals(
                        record.get("account_key", String.class),
                        record.get("account_type", String.class),
                        trimmed(record.get("currency_code", String.class)),
                        record.get("debits", BigDecimal.class),
                        record.get("credits", BigDecimal.class)));
    }

    private static String header(String predicate) {
        return """
                select id, bot_id, partition_id, bot_event_id, transaction_type, transaction_key,
                       source_type, source_id, currency_code, reversal_of_transaction_id,
                       occurred_at, description_code
                from trading.ledger_transactions
                where %s
                """.formatted(predicate);
    }

    private LedgerTransactionPersistenceView toView(Record header) {
        UUID transactionId = header.get("id", UUID.class);
        List<LedgerTransactionPersistenceView.EntryRow> entries = dsl.fetch("""
                        select entry.id, entry.bot_id, entry.partition_id, entry.transaction_id,
                               entry.ledger_account_id, entry.order_component_id, entry.entry_sequence,
                               entry.direction, entry.amount, entry.quantity, entry.entry_hash,
                               account.account_key, account.account_type,
                               account.currency_code as account_currency_code,
                               account.partition_id as account_partition_id
                        from trading.ledger_entries entry
                        join trading.ledger_accounts account on account.id = entry.ledger_account_id
                        where entry.transaction_id = ?
                        order by entry.entry_sequence
                        """, transactionId)
                .map(record -> new LedgerTransactionPersistenceView.EntryRow(
                        record.get("id", UUID.class),
                        record.get("bot_id", UUID.class),
                        record.get("partition_id", UUID.class),
                        record.get("transaction_id", UUID.class),
                        record.get("ledger_account_id", UUID.class),
                        record.get("account_key", String.class),
                        record.get("account_type", String.class),
                        trimmed(record.get("account_currency_code", String.class)),
                        record.get("account_partition_id", UUID.class),
                        record.get("order_component_id", UUID.class),
                        record.get("entry_sequence", Integer.class),
                        record.get("direction", String.class),
                        record.get("amount", BigDecimal.class),
                        record.get("quantity", BigDecimal.class),
                        record.get("entry_hash", String.class)));
        return new LedgerTransactionPersistenceView(
                transactionId,
                header.get("bot_id", UUID.class),
                header.get("partition_id", UUID.class),
                header.get("bot_event_id", UUID.class),
                header.get("transaction_type", String.class),
                header.get("transaction_key", String.class),
                header.get("source_type", String.class),
                header.get("source_id", UUID.class),
                trimmed(header.get("currency_code", String.class)),
                header.get("reversal_of_transaction_id", UUID.class),
                instant(header.get("occurred_at", OffsetDateTime.class)),
                header.get("description_code", String.class),
                entries);
    }

    /** The canonical currency columns are {@code char(3)}, which some drivers pad. */
    private static String trimmed(String value) {
        return value == null ? null : value.trim();
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    public record AccountTotals(
            String accountKey, String accountType, String currency, BigDecimal debits, BigDecimal credits) {}
}
