package com.idea2strategy.trading.worker.shorting;

import com.idea2strategy.trading.application.port.BorrowFeeAccrualStore;
import com.idea2strategy.trading.application.shorting.BorrowFeeAccrualService;
import com.idea2strategy.trading.domain.shorting.ShortRiskPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ShortingConfiguration {
    @Bean ShortRiskPolicy shortRiskPolicy() { return new ShortRiskPolicy(); }
    @Bean BorrowFeeAccrualService borrowFeeAccrualService(BorrowFeeAccrualStore store) {
        return new BorrowFeeAccrualService(store);
    }
}
