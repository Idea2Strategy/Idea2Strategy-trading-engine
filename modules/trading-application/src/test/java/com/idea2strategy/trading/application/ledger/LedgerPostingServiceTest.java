package com.idea2strategy.trading.application.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    private static final UUID BOT = UUID.fromString("b0000000-0000-4000-8000-000000000001");

    @Test
    void exposesFillIndependentPostingPortAndReplaysIdenticalCommand() {
        InMemoryStore store = new InMemoryStore();
        LedgerPostingService service = new LedgerPostingService(store);
        LedgerTransaction posting = posting(UUID.randomUUID());
        PostLedgerTransactionCommand command = PostLedgerTransactionCommand.botWide(BOT, posting);

        assertEquals(posting, service.post(command));
        assertEquals(posting, service.post(command));
        assertEquals(1, store.transactions.size());
    }

    /**
     * The bot event is the idempotency handle now. It is what the canonical
     * {@code trading.ledger_transactions.bot_event_id} unique enforces, so a second posting claiming
     * one event for different work is a conflict wherever it is checked.
     */
    @Test
    void oneBotEventCannotRecordTwoDifferentPostings() {
        InMemoryStore store = new InMemoryStore();
        LedgerPostingService service = new LedgerPostingService(store);
        UUID event = UUID.randomUUID();
        service.post(PostLedgerTransactionCommand.botWide(BOT, posting(event)));

        LedgerTransaction another = LedgerTransaction.standard(event, Instant.parse("2026-08-01T00:00:01Z"),
                List.of(LedgerEntryDraft.debit("FEE_EXPENSE", "USD", BigDecimal.ONE),
                        LedgerEntryDraft.credit("CASH", "USD", BigDecimal.ONE)));
        assertThrows(LedgerCommandConflictException.class,
                () -> service.post(PostLedgerTransactionCommand.botWide(BOT, another)));
    }

    @Test
    void aBotWideCommandCarriesNoPartition() {
        PostLedgerTransactionCommand command =
                PostLedgerTransactionCommand.botWide(BOT, posting(UUID.randomUUID()));

        assertEquals(BOT, command.botId());
        assertNull(command.partitionId());
    }

    private static LedgerTransaction posting(UUID sourceEventId) {
        return LedgerTransaction.standard(sourceEventId, Instant.parse("2026-08-01T00:00:00Z"),
                List.of(LedgerEntryDraft.debit("SECURITY", "USD", new BigDecimal("10.50")),
                        LedgerEntryDraft.credit("CASH", "USD", new BigDecimal("10.50"))));
    }

    private static final class InMemoryStore implements LedgerStore {
        private final Map<UUID, LedgerTransaction> transactions = new HashMap<>();
        private final Map<UUID, LedgerTransaction> byBotEvent = new HashMap<>();

        @Override
        public LedgerTransaction append(PostLedgerTransactionCommand command) {
            LedgerTransaction posting = command.transaction();
            LedgerTransaction prior = byBotEvent.get(posting.sourceEventId());
            if (prior != null) {
                if (!prior.equals(posting)) throw new LedgerCommandConflictException("bot event conflict");
                return prior;
            }
            transactions.put(posting.transactionId(), posting);
            byBotEvent.put(posting.sourceEventId(), posting);
            return posting;
        }
    }
}
