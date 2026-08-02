package com.idea2strategy.trading.worker.position;

import com.idea2strategy.trading.application.port.PositionLotStore;
import com.idea2strategy.trading.application.position.PositionLotService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
public class PositionLotConfiguration {
    @Bean PositionLotService positionLotService(PositionLotStore store){return new PositionLotService(store);}
}
