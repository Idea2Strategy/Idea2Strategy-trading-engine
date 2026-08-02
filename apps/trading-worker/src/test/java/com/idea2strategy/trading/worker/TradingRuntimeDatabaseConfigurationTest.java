package com.idea2strategy.trading.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

class TradingRuntimeDatabaseConfigurationTest {

    @Test
    void disablesRuntimeFlywayAndKeepsHibernateValidateOnly() throws IOException {
        var sources = new YamlPropertySourceLoader()
                .load("trading-worker-application", new ClassPathResource("application.yaml"));
        var source = sources.getFirst();

        assertEquals(false, source.getProperty("spring.flyway.enabled"));
        assertEquals("validate", source.getProperty("spring.jpa.hibernate.ddl-auto"));
    }
}
