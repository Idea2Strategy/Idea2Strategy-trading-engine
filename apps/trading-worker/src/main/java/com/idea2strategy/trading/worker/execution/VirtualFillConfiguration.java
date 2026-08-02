package com.idea2strategy.trading.worker.execution;

import com.idea2strategy.trading.application.execution.VirtualFillService;
import com.idea2strategy.trading.application.port.FillDecisionStore;
import com.idea2strategy.trading.domain.execution.RealisticFillModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class VirtualFillConfiguration {
    @Bean
    RealisticFillModel realisticFillModel() { return new RealisticFillModel(); }

    @Bean
    VirtualFillService virtualFillService(RealisticFillModel model, FillDecisionStore store) {
        return new VirtualFillService(model, store);
    }
}
