package com.idea2strategy.trading.domain.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LedgerTransactionTest {
    private static final UUID SOURCE = UUID.fromString("00000000-0000-0000-0000-000000000501");
    private static final Instant POSTED_AT = Instant.parse("2026-07-31T14:30:00.200Z");

    @Test
    void createsBalancedFixtureCompatiblePostingWithDeterministicIdentity() {
        var first = LedgerTransaction.standard(SOURCE, POSTED_AT, List.of(
                LedgerEntryDraft.debit("SECURITY", "USD", new BigDecimal("210.12")),
                LedgerEntryDraft.credit("CASH", "USD", new BigDecimal("210.120"))));
        var retry = LedgerTransaction.standard(SOURCE, POSTED_AT, List.of(
                LedgerEntryDraft.debit("SECURITY", "USD", new BigDecimal("210.1200")),
                LedgerEntryDraft.credit("CASH", "USD", new BigDecimal("210.12"))));

        assertEquals(first.transactionId(), retry.transactionId());
        assertEquals(first.entries().stream().map(LedgerEntry::entryId).toList(),
                retry.entries().stream().map(LedgerEntry::entryId).toList());
        assertEquals(SOURCE, first.sourceEventId());
        assertEquals(0, first.debitTotal("USD").compareTo(first.creditTotal("USD")));
    }

    @Test
    void rejectsUnbalancedMixedCurrencyAndNonPositiveEntries() {
        assertThrows(IllegalArgumentException.class, () -> LedgerTransaction.standard(SOURCE, POSTED_AT, List.of(
                LedgerEntryDraft.debit("SECURITY", "USD", new BigDecimal("210.12")),
                LedgerEntryDraft.credit("CASH", "USD", new BigDecimal("210.11")))));
        assertThrows(IllegalArgumentException.class, () -> LedgerTransaction.standard(SOURCE, POSTED_AT, List.of(
                LedgerEntryDraft.debit("SECURITY", "USD", BigDecimal.ONE),
                LedgerEntryDraft.credit("CASH", "KRW", BigDecimal.ONE))));
        assertThrows(IllegalArgumentException.class,
                () -> LedgerEntryDraft.debit("SECURITY", "USD", BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> LedgerEntryDraft.debit("SECURITY", "usd", BigDecimal.ONE));
    }

    @Test
    void reversalIsAnOppositeAppendOnlyTransactionWithStableLineage() {
        var original = LedgerTransaction.standard(SOURCE, POSTED_AT, List.of(
                LedgerEntryDraft.debit("SECURITY", "USD", new BigDecimal("210.12")),
                LedgerEntryDraft.credit("CASH", "USD", new BigDecimal("210.12"))));
        UUID reversalSource = UUID.randomUUID();
        var reversal = LedgerTransaction.reversal(reversalSource, POSTED_AT.plusSeconds(1), original);

        assertNotEquals(original.transactionId(), reversal.transactionId());
        assertEquals(LedgerPostingKind.REVERSAL, reversal.kind());
        assertEquals(original.transactionId(), reversal.reversesTransactionId());
        assertEquals(LedgerDirection.CREDIT, reversal.entries().get(0).direction());
        assertEquals(LedgerDirection.DEBIT, reversal.entries().get(1).direction());
        assertThrows(IllegalArgumentException.class, () -> LedgerTransaction.restore(
                reversal.transactionId(), reversal.sourceEventId(), reversal.postedAt(), reversal.kind(),
                UUID.randomUUID(), null, reversal.entries()));
    }

    @Test
    void correctionKeepsOriginalLineageWithoutMutatingEitherPosting() {
        var original = LedgerTransaction.standard(SOURCE, POSTED_AT, List.of(
                LedgerEntryDraft.debit("SECURITY", "USD", new BigDecimal("210.12")),
                LedgerEntryDraft.credit("CASH", "USD", new BigDecimal("210.12"))));
        var correction = LedgerTransaction.correction(UUID.randomUUID(), POSTED_AT.plusSeconds(2),
                original.transactionId(), List.of(
                        LedgerEntryDraft.debit("SECURITY", "USD", new BigDecimal("0.02")),
                        LedgerEntryDraft.credit("CASH", "USD", new BigDecimal("0.02"))));

        assertEquals(LedgerPostingKind.CORRECTION, correction.kind());
        assertEquals(original.transactionId(), correction.correctsTransactionId());
        assertEquals(new BigDecimal("210.12"), original.entries().get(0).amount());
    }

    @Test
    void normalizesPostingTimeToPostgresqlMicrosecondPrecisionBeforeIdentityGeneration() {
        Instant nanosecondTime = Instant.parse("2026-08-01T00:00:00.123456789Z");
        var posting = LedgerTransaction.standard(SOURCE, nanosecondTime, List.of(
                LedgerEntryDraft.debit("SECURITY", "USD", BigDecimal.ONE),
                LedgerEntryDraft.credit("CASH", "USD", BigDecimal.ONE)));

        assertEquals(Instant.parse("2026-08-01T00:00:00.123456Z"), posting.postedAt());
        assertEquals(posting, LedgerTransaction.restore(posting.transactionId(), posting.sourceEventId(),
                posting.postedAt(), posting.kind(), null, null, posting.entries()));
    }
}
