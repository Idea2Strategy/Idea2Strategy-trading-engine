package com.idea2strategy.trading.worker.fill;

import com.idea2strategy.trading.application.fill.FillRecordService;
import com.idea2strategy.trading.application.port.FillRecordStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class FillRecordConfiguration {
    @Bean FillRecordService fillRecordService(FillRecordStore store) { return new FillRecordService(store); }
}
