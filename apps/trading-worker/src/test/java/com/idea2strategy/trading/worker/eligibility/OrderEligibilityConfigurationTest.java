package com.idea2strategy.trading.worker.eligibility;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.idea2strategy.trading.application.eligibility.OrderEligibilityService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class OrderEligibilityConfigurationTest {
    @Test
    void wiresExplicitEligibilityPolicyIntoApplicationService() {
        try (var context = new AnnotationConfigApplicationContext(OrderEligibilityConfiguration.class)) {
            assertNotNull(context.getBean(OrderEligibilityService.class));
        }
    }
}
