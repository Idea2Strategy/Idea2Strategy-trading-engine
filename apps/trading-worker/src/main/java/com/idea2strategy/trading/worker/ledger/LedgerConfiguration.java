package com.idea2strategy.trading.worker.ledger;

import com.idea2strategy.trading.application.ledger.LedgerPostingService;
import com.idea2strategy.trading.application.port.LedgerStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class LedgerConfiguration {
    @Bean
    LedgerPostingService ledgerPostingService(LedgerStore store) {
        return new LedgerPostingService(store);
    }
}
