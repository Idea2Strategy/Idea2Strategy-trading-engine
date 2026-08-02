package com.idea2strategy.trading.application.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.idea2strategy.trading.application.port.LedgerStore;
import com.idea2strategy.trading.domain.ledger.LedgerEntryDraft;
import com.idea2strategy.trading.domain.ledger.LedgerTransaction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LedgerPostingServiceTest {
    @Test
    void exposesFillIndependentPostingPortAndReplaysIdenticalCommand() {
        InMemoryStore store = new InMemoryStore();
        LedgerPostingService service = new LedgerPostingService(store);
        LedgerTransaction posting = posting();
        UUID commandId = UUID.randomUUID();
        PostLedgerTransactionCommand command = new PostLedgerTransactionCommand(commandId, posting);

        assertEquals(posting, service.post(command));
        assertEquals(posting, service.post(command));
        assertEquals(1, store.transactions.size());
    }

    @Test
    void commandIdentityCannotBeReusedForAnotherMeaning() {
        InMemoryStore store = new InMemoryStore();
        LedgerPostingService service = new LedgerPostingService(store);
        UUID commandId = UUID.randomUUID();
        service.post(new PostLedgerTransactionCommand(commandId, posting()));

        LedgerTransaction another = LedgerTransaction.standard(UUID.randomUUID(), Instant.parse("2026-08-01T00:00:01Z"),
                List.of(LedgerEntryDraft.debit("FEE_EXPENSE", "USD", BigDecimal.ONE),
                        LedgerEntryDraft.credit("CASH", "USD", BigDecimal.ONE)));
        assertThrows(LedgerCommandConflictException.class,
                () -> service.post(new PostLedgerTransactionCommand(commandId, another)));
    }

    private static LedgerTransaction posting() {
        return LedgerTransaction.standard(UUID.randomUUID(), Instant.parse("2026-08-01T00:00:00Z"),
                List.of(LedgerEntryDraft.debit("SECURITY", "USD", new BigDecimal("10.50")),
                        LedgerEntryDraft.credit("CASH", "USD", new BigDecimal("10.50"))));
    }

    private static final class InMemoryStore implements LedgerStore {
        private final Map<UUID, LedgerTransaction> transactions = new HashMap<>();
        private final Map<UUID, LedgerTransaction> commands = new HashMap<>();

        @Override
        public LedgerTransaction append(PostLedgerTransactionCommand command) {
            LedgerTransaction prior = commands.get(command.commandId());
            if (prior != null) {
                if (!prior.equals(command.transaction())) throw new LedgerCommandConflictException("command conflict");
                return prior;
            }
            transactions.put(command.transaction().transactionId(), command.transaction());
            commands.put(command.commandId(), command.transaction());
            return command.transaction();
        }
    }
}
