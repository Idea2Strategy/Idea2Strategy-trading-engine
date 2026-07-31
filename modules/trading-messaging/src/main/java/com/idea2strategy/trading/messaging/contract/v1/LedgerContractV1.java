package com.idea2strategy.trading.messaging.contract.v1;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class LedgerContractV1 {
    private LedgerContractV1() {
    }

    public enum Direction { DEBIT, CREDIT }

    public record Entry(
        UUID entryId,
        String accountCode,
        Direction direction,
        CurrencyAmountV1 amount,
        UUID sourceEventId
    ) {
        public Entry {
            ContractValidationV1.required(entryId, "entryId");
            ContractValidationV1.requiredText(accountCode, "accountCode");
            ContractValidationV1.required(direction, "direction");
            ContractValidationV1.required(amount, "amount");
            ContractValidationV1.required(sourceEventId, "sourceEventId");
        }
    }

    public record Transaction(
        UUID transactionId,
        UUID sourceEventId,
        Instant postedAt,
        List<Entry> entries
    ) {
        public Transaction {
            ContractValidationV1.required(transactionId, "transactionId");
            ContractValidationV1.required(sourceEventId, "sourceEventId");
            ContractValidationV1.utcInstant(postedAt, "postedAt");
            entries = List.copyOf(ContractValidationV1.required(entries, "entries"));
            if (entries.size() < 2) {
                throw new IllegalArgumentException("entries must contain at least two entries");
            }
            validateBalanced(entries);
        }

        private static void validateBalanced(List<Entry> entries) {
            Map<String, BigDecimal> debitTotals = new HashMap<>();
            Map<String, BigDecimal> creditTotals = new HashMap<>();

            for (Entry entry : entries) {
                ContractValidationV1.required(entry, "entry");
                var totals = entry.direction() == Direction.DEBIT ? debitTotals : creditTotals;
                totals.merge(entry.amount().currency(), entry.amount().amount().asBigDecimal(), BigDecimal::add);
            }

            for (String currency : debitTotals.keySet()) {
                if (debitTotals.get(currency).compareTo(creditTotals.getOrDefault(currency, BigDecimal.ZERO)) != 0) {
                    throw new IllegalArgumentException("ledger transaction must balance by currency");
                }
            }
            for (String currency : creditTotals.keySet()) {
                if (!debitTotals.containsKey(currency)) {
                    throw new IllegalArgumentException("ledger transaction must balance by currency");
                }
            }
        }
    }
}
