package com.idea2strategy.trading.worker.eligibility;

import com.idea2strategy.trading.application.eligibility.OrderEligibilityService;
import com.idea2strategy.trading.domain.eligibility.OrderEligibilityPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OrderEligibilityConfiguration {
    @Bean
    OrderEligibilityPolicy orderEligibilityPolicy() {
        return new OrderEligibilityPolicy();
    }

    @Bean
    OrderEligibilityService orderEligibilityService(OrderEligibilityPolicy policy) {
        return new OrderEligibilityService(policy);
    }
}
