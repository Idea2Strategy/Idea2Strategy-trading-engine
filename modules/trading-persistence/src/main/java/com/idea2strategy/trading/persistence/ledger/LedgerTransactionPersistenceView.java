package com.idea2strategy.trading.persistence.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the canonical ledger tables actually hold, read back column for column.
 *
 * <p>Values stay in their stored form rather than being mapped into the domain. The point of this
 * projection is to check that a posting landed in the canonical shape, and translating on the way
 * out would hide exactly the mistakes it exists to catch.
 */
public record LedgerTransactionPersistenceView(
        UUID transactionId,
        UUID botId,
        UUID partitionId,
        UUID botEventId,
        String transactionType,
        String transactionKey,
        String sourceType,
        UUID sourceId,
        String currencyCode,
        UUID reversalOfTransactionId,
        Instant occurredAt,
        String descriptionCode,
        List<EntryRow> entries) {

    public LedgerTransactionPersistenceView {
        entries = List.copyOf(entries);
    }

    public record EntryRow(
            UUID entryId,
            UUID botId,
            UUID partitionId,
            UUID transactionId,
            UUID ledgerAccountId,
            String accountKey,
            String accountType,
            String accountCurrencyCode,
            UUID accountPartitionId,
            UUID orderComponentId,
            int entrySequence,
            String direction,
            BigDecimal amount,
            BigDecimal quantity,
            String entryHash) {
    }
}
