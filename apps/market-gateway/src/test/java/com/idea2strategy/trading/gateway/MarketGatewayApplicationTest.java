package com.idea2strategy.trading.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest
class MarketGatewayApplicationTest {
    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoadsWithThePublishingPathDormantWhenUnconfigured() {
        assertEquals(0, context.getBeanNamesForType(MarketGatewayRunner.class).length);
    }
}
