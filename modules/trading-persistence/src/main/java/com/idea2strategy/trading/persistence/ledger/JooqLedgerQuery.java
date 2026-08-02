package com.idea2strategy.trading.persistence.ledger;

import com.idea2strategy.trading.domain.ledger.LedgerDirection;
import com.idea2strategy.trading.domain.ledger.LedgerEntry;
import com.idea2strategy.trading.domain.ledger.LedgerPostingKind;
import com.idea2strategy.trading.domain.ledger.LedgerTransaction;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
public class JooqLedgerQuery {
    private final DSLContext dsl;

    public JooqLedgerQuery(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    public Optional<LedgerTransaction> findByTransactionId(UUID transactionId) {
        return dsl.select().from("trading.official_ledger_transaction")
                .where(DSL.field("transaction_id", UUID.class).eq(transactionId))
                .fetchOptional(this::transaction);
    }

    public Optional<LedgerTransaction> findBySourceEventId(UUID sourceEventId) {
        return dsl.select().from("trading.official_ledger_transaction")
                .where(DSL.field("source_event_id", UUID.class).eq(sourceEventId))
                .fetchOptional(this::transaction);
    }

    public List<AccountTotals> accountTotals() {
        var account = DSL.field("account_code", String.class);
        var currency = DSL.field("currency", String.class);
        var direction = DSL.field("direction", String.class);
        var amount = DSL.field("amount", BigDecimal.class);
        var debit = DSL.sum(DSL.when(direction.eq("DEBIT"), amount).otherwise(BigDecimal.ZERO));
        var credit = DSL.sum(DSL.when(direction.eq("CREDIT"), amount).otherwise(BigDecimal.ZERO));
        return dsl.select(account, currency, debit, credit)
                .from("trading.official_ledger_entry").groupBy(account, currency)
                .orderBy(account, currency).fetch(record -> new AccountTotals(
                        record.get(account), record.get(currency).trim(), record.get(debit), record.get(credit)));
    }

    private LedgerTransaction transaction(Record record) {
        UUID id = record.get("transaction_id", UUID.class);
        var entryId = DSL.field("entry_id", UUID.class);
        var sequence = DSL.field("entry_sequence", Integer.class);
        List<LedgerEntry> entries = dsl.select().from("trading.official_ledger_entry")
                .where(DSL.field("transaction_id", UUID.class).eq(id)).orderBy(sequence)
                .fetch(entry -> new LedgerEntry(entry.get(entryId), entry.get(sequence),
                        entry.get("account_code", String.class),
                        LedgerDirection.valueOf(entry.get("direction", String.class)),
                        entry.get("currency", String.class).trim(), entry.get("amount", BigDecimal.class),
                        entry.get("source_event_id", UUID.class)));
        return LedgerTransaction.restore(id, record.get("source_event_id", UUID.class),
                record.get("posted_at", OffsetDateTime.class).toInstant(),
                LedgerPostingKind.valueOf(record.get("posting_kind", String.class)),
                record.get("reverses_transaction_id", UUID.class),
                record.get("corrects_transaction_id", UUID.class), entries);
    }

    public record AccountTotals(String accountCode, String currency, BigDecimal debits, BigDecimal credits) {}
}
