package com.idea2strategy.trading.persistence.ledger;

import com.idea2strategy.trading.application.ledger.LedgerCommandConflictException;
import com.idea2strategy.trading.application.ledger.PostLedgerTransactionCommand;
import com.idea2strategy.trading.application.port.LedgerStore;
import com.idea2strategy.trading.domain.ledger.LedgerDirection;
import com.idea2strategy.trading.domain.ledger.LedgerEntry;
import com.idea2strategy.trading.domain.ledger.LedgerPostingKind;
import com.idea2strategy.trading.domain.ledger.LedgerTransaction;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class PostgresLedgerStore implements LedgerStore {
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
        String fingerprint = fingerprint(command.transaction());
        Optional<Receipt> receipt = receipt(command.commandId());
        if (receipt.isPresent()) return replay(receipt.orElseThrow(), command, fingerprint);

        validateLineage(command.transaction());
        int inserted = jdbc.sql("""
                insert into trading.official_ledger_transaction
                    (transaction_id, source_event_id, posted_at, posting_kind,
                     reverses_transaction_id, corrects_transaction_id)
                values (:id, :source, :postedAt, :kind, :reverses, :corrects)
                on conflict do nothing
                """).param("id", command.transaction().transactionId())
                .param("source", command.transaction().sourceEventId())
                .param("postedAt", offset(command.transaction().postedAt()))
                .param("kind", command.transaction().kind().name())
                .param("reverses", command.transaction().reversesTransactionId())
                .param("corrects", command.transaction().correctsTransactionId()).update();

        if (inserted == 1) {
            insertEntries(command.transaction());
        } else {
            LedgerTransaction existing = load(command.transaction().transactionId()).orElseThrow(this::conflict);
            if (!existing.equals(command.transaction())) throw conflict();
        }

        int receiptInserted = jdbc.sql("""
                insert into trading.official_ledger_command_receipt
                    (command_id, request_fingerprint, transaction_id)
                values (:commandId, :fingerprint, :transactionId)
                on conflict do nothing
                """).param("commandId", command.commandId()).param("fingerprint", fingerprint)
                .param("transactionId", command.transaction().transactionId()).update();
        if (receiptInserted == 0) {
            return replay(receipt(command.commandId()).orElseThrow(this::conflict), command, fingerprint);
        }
        return command.transaction();
    }

    private void validateLineage(LedgerTransaction posting) {
        UUID referenced = posting.kind() == LedgerPostingKind.REVERSAL
                ? posting.reversesTransactionId() : posting.correctsTransactionId();
        if (referenced == null) return;
        LedgerTransaction original = load(referenced).orElseThrow(() ->
                new LedgerCommandConflictException("referenced ledger transaction does not exist"));
        if (posting.kind() == LedgerPostingKind.REVERSAL) {
            if (posting.entries().size() != original.entries().size()) throw conflict();
            for (int index = 0; index < original.entries().size(); index++) {
                LedgerEntry expected = original.entries().get(index);
                LedgerEntry actual = posting.entries().get(index);
                if (!expected.accountCode().equals(actual.accountCode())
                        || expected.direction().opposite() != actual.direction()
                        || !expected.currency().equals(actual.currency())
                        || expected.amount().compareTo(actual.amount()) != 0) {
                    throw new LedgerCommandConflictException("reversal must exactly negate the referenced transaction");
                }
            }
        }
    }

    private void insertEntries(LedgerTransaction posting) {
        for (LedgerEntry entry : posting.entries()) {
            int count = jdbc.sql("""
                    insert into trading.official_ledger_entry
                        (entry_id, transaction_id, entry_sequence, account_code, direction,
                         currency, amount, source_event_id)
                    values (:entryId, :transactionId, :sequence, :account, :direction,
                            :currency, :amount, :source)
                    """).param("entryId", entry.entryId()).param("transactionId", posting.transactionId())
                    .param("sequence", entry.sequence()).param("account", entry.accountCode())
                    .param("direction", entry.direction().name()).param("currency", entry.currency())
                    .param("amount", entry.amount()).param("source", entry.sourceEventId()).update();
            if (count != 1) throw conflict();
        }
    }

    private LedgerTransaction replay(Receipt receipt, PostLedgerTransactionCommand command, String fingerprint) {
        if (!receipt.transactionId().equals(command.transaction().transactionId())
                || !receipt.fingerprint().equals(fingerprint)) throw conflict();
        return load(receipt.transactionId()).orElseThrow(this::conflict);
    }

    Optional<LedgerTransaction> load(UUID transactionId) {
        return jdbc.sql("select * from trading.official_ledger_transaction where transaction_id=:id")
                .param("id", transactionId).query((rs, row) -> map(rs)).optional();
    }

    private LedgerTransaction map(ResultSet rs) throws SQLException {
        UUID transactionId = rs.getObject("transaction_id", UUID.class);
        List<LedgerEntry> entries = jdbc.sql("""
                select * from trading.official_ledger_entry
                where transaction_id=:id order by entry_sequence
                """).param("id", transactionId).query((entry, row) -> new LedgerEntry(
                        entry.getObject("entry_id", UUID.class), entry.getInt("entry_sequence"),
                        entry.getString("account_code"), LedgerDirection.valueOf(entry.getString("direction")),
                        entry.getString("currency").trim(), entry.getBigDecimal("amount"),
                        entry.getObject("source_event_id", UUID.class))).list();
        return LedgerTransaction.restore(transactionId, rs.getObject("source_event_id", UUID.class),
                rs.getObject("posted_at", OffsetDateTime.class).toInstant(),
                LedgerPostingKind.valueOf(rs.getString("posting_kind")),
                rs.getObject("reverses_transaction_id", UUID.class),
                rs.getObject("corrects_transaction_id", UUID.class), entries);
    }

    private Optional<Receipt> receipt(UUID commandId) {
        return jdbc.sql("""
                select request_fingerprint, transaction_id
                from trading.official_ledger_command_receipt where command_id=:commandId
                """).param("commandId", commandId).query((rs, row) -> new Receipt(
                        rs.getString("request_fingerprint"), rs.getObject("transaction_id", UUID.class))).optional();
    }

    private static String fingerprint(LedgerTransaction transaction) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(transaction.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private LedgerCommandConflictException conflict() {
        return new LedgerCommandConflictException("ledger command or transaction identity conflict");
    }

    private static OffsetDateTime offset(java.time.Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private record Receipt(String fingerprint, UUID transactionId) {}
}
