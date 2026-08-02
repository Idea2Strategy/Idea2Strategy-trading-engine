package com.idea2strategy.trading.domain.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record LedgerTransaction(
        UUID transactionId,
        UUID sourceEventId,
        Instant postedAt,
        LedgerPostingKind kind,
        UUID reversesTransactionId,
        UUID correctsTransactionId,
        List<LedgerEntry> entries) {

    public LedgerTransaction {
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(sourceEventId, "sourceEventId");
        postedAt = Objects.requireNonNull(postedAt, "postedAt").truncatedTo(ChronoUnit.MICROS);
        Objects.requireNonNull(kind, "kind");
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        validateLineage(kind, reversesTransactionId, correctsTransactionId);
        validateEntries(transactionId, sourceEventId, entries);
        validateBalanced(entries);
        UUID expected = LedgerIdentity.transaction(canonical(sourceEventId, postedAt, kind,
                reversesTransactionId, correctsTransactionId, entries.stream().map(LedgerEntry::toDraft).toList()));
        if (!expected.equals(transactionId)) throw new IllegalArgumentException("transactionId does not match posting meaning");
    }

    public static LedgerTransaction standard(UUID sourceEventId, Instant postedAt, List<LedgerEntryDraft> entries) {
        return create(sourceEventId, postedAt, LedgerPostingKind.STANDARD, null, null, entries);
    }

    public static LedgerTransaction reversal(UUID sourceEventId, Instant postedAt, LedgerTransaction original) {
        Objects.requireNonNull(original, "original");
        return create(sourceEventId, postedAt, LedgerPostingKind.REVERSAL, original.transactionId(), null,
                original.entries().stream().map(LedgerEntry::toDraft).map(LedgerEntryDraft::opposite).toList());
    }

    public static LedgerTransaction correction(UUID sourceEventId, Instant postedAt, UUID correctsTransactionId,
                                                List<LedgerEntryDraft> entries) {
        return create(sourceEventId, postedAt, LedgerPostingKind.CORRECTION, null,
                Objects.requireNonNull(correctsTransactionId, "correctsTransactionId"), entries);
    }

    public static LedgerTransaction restore(UUID transactionId, UUID sourceEventId, Instant postedAt,
                                            LedgerPostingKind kind, UUID reversesTransactionId,
                                            UUID correctsTransactionId, List<LedgerEntry> entries) {
        return new LedgerTransaction(transactionId, sourceEventId, postedAt, kind,
                reversesTransactionId, correctsTransactionId, entries);
    }

    private static LedgerTransaction create(UUID sourceEventId, Instant postedAt, LedgerPostingKind kind,
                                            UUID reversesTransactionId, UUID correctsTransactionId,
                                            List<LedgerEntryDraft> drafts) {
        Objects.requireNonNull(sourceEventId, "sourceEventId");
        postedAt = Objects.requireNonNull(postedAt, "postedAt").truncatedTo(ChronoUnit.MICROS);
        drafts = List.copyOf(Objects.requireNonNull(drafts, "entries"));
        UUID transactionId = LedgerIdentity.transaction(canonical(sourceEventId, postedAt, kind,
                reversesTransactionId, correctsTransactionId, drafts));
        List<LedgerEntry> entries = new ArrayList<>();
        for (int index = 0; index < drafts.size(); index++) {
            LedgerEntryDraft draft = Objects.requireNonNull(drafts.get(index), "entry");
            entries.add(new LedgerEntry(LedgerIdentity.entry(transactionId, index + 1), index + 1,
                    draft.accountCode(), draft.direction(), draft.currency(), draft.amount(), sourceEventId));
        }
        return new LedgerTransaction(transactionId, sourceEventId, postedAt, kind,
                reversesTransactionId, correctsTransactionId, entries);
    }

    public BigDecimal debitTotal(String currency) {
        return total(currency, LedgerDirection.DEBIT);
    }

    public BigDecimal creditTotal(String currency) {
        return total(currency, LedgerDirection.CREDIT);
    }

    private BigDecimal total(String currency, LedgerDirection direction) {
        return entries.stream().filter(entry -> entry.currency().equals(currency) && entry.direction() == direction)
                .map(LedgerEntry::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static void validateLineage(LedgerPostingKind kind, UUID reversal, UUID correction) {
        if (kind == LedgerPostingKind.STANDARD && (reversal != null || correction != null)) {
            throw new IllegalArgumentException("standard posting cannot have lineage");
        }
        if (kind == LedgerPostingKind.REVERSAL && (reversal == null || correction != null)) {
            throw new IllegalArgumentException("reversal requires only reversesTransactionId");
        }
        if (kind == LedgerPostingKind.CORRECTION && (correction == null || reversal != null)) {
            throw new IllegalArgumentException("correction requires only correctsTransactionId");
        }
    }

    private static void validateEntries(UUID transactionId, UUID sourceEventId, List<LedgerEntry> entries) {
        if (entries.size() < 2) throw new IllegalArgumentException("entries must contain at least two entries");
        Set<UUID> ids = new HashSet<>();
        for (int index = 0; index < entries.size(); index++) {
            LedgerEntry entry = Objects.requireNonNull(entries.get(index), "entry");
            int sequence = index + 1;
            if (entry.sequence() != sequence) throw new IllegalArgumentException("entry sequence must be contiguous");
            if (!entry.entryId().equals(LedgerIdentity.entry(transactionId, sequence))) {
                throw new IllegalArgumentException("entryId does not match transaction identity");
            }
            if (!ids.add(entry.entryId())) throw new IllegalArgumentException("duplicate entryId");
            if (!sourceEventId.equals(entry.sourceEventId())) {
                throw new IllegalArgumentException("entry sourceEventId must match transaction sourceEventId");
            }
        }
    }

    /**
     * The currency this whole posting is denominated in.
     *
     * <p>{@code trading.ledger_transactions.currency_code} is a single NOT NULL header currency and
     * {@code trading.ledger_entries} carries none of its own, so a posting has exactly one.
     */
    public String currency() {
        return entries.getFirst().currency();
    }

    /**
     * One currency, debits equal to credits.
     *
     * <p>A multi currency posting used to be legal here and balanced per currency. The canonical
     * ledger cannot record one: the header names a single currency and the deferred balance trigger
     * sums one signed total across every entry of a transaction, so the halves of a two currency
     * posting would have to net to zero against each other. Splitting such a posting into one
     * transaction per currency is a decision for the caller that raises it, not something this
     * record can make on its behalf, so it is refused rather than silently split.
     */
    private static void validateBalanced(List<LedgerEntry> entries) {
        Set<String> currencies = new HashSet<>();
        Map<LedgerDirection, BigDecimal> totals = new HashMap<>();
        for (LedgerEntry entry : entries) {
            currencies.add(entry.currency());
            totals.merge(entry.direction(), entry.amount(), BigDecimal::add);
        }
        if (currencies.size() != 1) {
            throw new IllegalArgumentException("ledger transaction must be denominated in one currency");
        }
        if (totals.getOrDefault(LedgerDirection.DEBIT, BigDecimal.ZERO)
                .compareTo(totals.getOrDefault(LedgerDirection.CREDIT, BigDecimal.ZERO)) != 0) {
            throw new IllegalArgumentException("ledger transaction must be balanced by currency");
        }
    }

    private static String canonical(UUID sourceEventId, Instant postedAt, LedgerPostingKind kind,
                                    UUID reverses, UUID corrects, List<LedgerEntryDraft> drafts) {
        StringBuilder value = new StringBuilder("LEDGER|1|").append(sourceEventId).append('|')
                .append(postedAt).append('|').append(kind).append('|')
                .append(reverses == null ? "-" : reverses).append('|')
                .append(corrects == null ? "-" : corrects);
        for (LedgerEntryDraft draft : drafts) {
            value.append('|').append(draft.accountCode()).append('|').append(draft.direction())
                    .append('|').append(draft.currency()).append('|').append(draft.amount().toPlainString());
        }
        return value.toString();
    }
}
