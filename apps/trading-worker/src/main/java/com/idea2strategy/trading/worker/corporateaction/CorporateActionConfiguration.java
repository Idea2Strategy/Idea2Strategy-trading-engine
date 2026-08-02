package com.idea2strategy.trading.worker.corporateaction;
import com.idea2strategy.trading.application.corporateaction.CorporateActionService;
import com.idea2strategy.trading.application.port.CorporateActionStore;
import org.springframework.context.annotation.Bean;import org.springframework.context.annotation.Configuration;
@Configuration(proxyBeanMethods=false) public class CorporateActionConfiguration{@Bean CorporateActionService corporateActionService(CorporateActionStore store){return new CorporateActionService(store);}}
