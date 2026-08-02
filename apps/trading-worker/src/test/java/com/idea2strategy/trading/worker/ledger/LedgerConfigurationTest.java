package com.idea2strategy.trading.worker.ledger;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.idea2strategy.trading.application.ledger.LedgerPostingService;
import com.idea2strategy.trading.application.ledger.PostLedgerTransactionCommand;
import com.idea2strategy.trading.application.port.LedgerStore;
import com.idea2strategy.trading.domain.ledger.LedgerTransaction;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class LedgerConfigurationTest {
    @Test
    void wiresFillIndependentPostingService() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(LedgerStore.class, () -> new LedgerStore() {
                @Override
                public LedgerTransaction append(PostLedgerTransactionCommand command) {
                    return command.transaction();
                }
            });
            context.register(LedgerConfiguration.class);
            context.refresh();
            assertNotNull(context.getBean(LedgerPostingService.class));
        }
    }
}
