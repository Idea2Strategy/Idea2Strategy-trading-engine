package com.idea2strategy.trading.messaging.contract.v1;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerContractV1Test {

    @Test
    void defensivelyCopiesTransactionEntries() {
        var entries = new ArrayList<>(balancedEntries());
        var transaction = transaction(entries);

        entries.clear();

        assertThat(transaction.entries()).hasSize(2);
    }

    @Test
    void rejectsTransactionWithFewerThanTwoEntries() {
        assertThatThrownBy(() -> transaction(List.of(debit("USD", "100"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least two entries");
    }

    @Test
    void rejectsTransactionWithImbalancedDebitAndCreditTotals() {
        assertThatThrownBy(() -> transaction(List.of(
            debit("USD", "100"),
            credit("USD", "99")
        ))).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("balance by currency");
    }

    @Test
    void reportsUnbalancedTransactionAsBalancedValidationFailure() {
        assertThatThrownBy(() -> unbalancedTransaction())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("balanced");
    }

    @Test
    void rejectsTransactionWithDebitAndCreditInDifferentCurrencies() {
        assertThatThrownBy(() -> transaction(List.of(
            debit("USD", "100"),
            credit("EUR", "100")
        ))).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("balance by currency");
    }

    private List<LedgerContractV1.Entry> balancedEntries() {
        return List.of(debit("USD", "100"), credit("USD", "100"));
    }

    private LedgerContractV1.Transaction transaction(List<LedgerContractV1.Entry> entries) {
        return new LedgerContractV1.Transaction(
            UUID.fromString("61111111-1111-1111-1111-111111111111"),
            UUID.fromString("62222222-2222-2222-2222-222222222222"),
            Instant.parse("2026-07-31T00:00:00Z"),
            entries
        );
    }

    private LedgerContractV1.Transaction unbalancedTransaction() {
        return transaction(List.of(debit("USD", "100"), credit("USD", "99")));
    }

    private LedgerContractV1.Entry debit(String currency, String amount) {
        return entry(
            "63333333-3333-3333-3333-333333333333",
            "CASH",
            LedgerContractV1.Direction.DEBIT,
            currency,
            amount
        );
    }

    private LedgerContractV1.Entry credit(String currency, String amount) {
        return entry(
            "64444444-4444-4444-4444-444444444444",
            "EXECUTED_ORDERS",
            LedgerContractV1.Direction.CREDIT,
            currency,
            amount
        );
    }

    private LedgerContractV1.Entry entry(
        String entryId,
        String accountCode,
        LedgerContractV1.Direction direction,
        String currency,
        String amount
    ) {
        return new LedgerContractV1.Entry(
            UUID.fromString(entryId),
            accountCode,
            direction,
            new CurrencyAmountV1(currency, new DecimalValueV1(amount)),
            UUID.fromString("65555555-5555-5555-5555-555555555555")
        );
    }
}
