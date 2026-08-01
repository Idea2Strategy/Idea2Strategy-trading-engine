package com.idea2strategy.trading.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = "com.idea2strategy.trading")
@EntityScan("com.idea2strategy.trading.persistence")
@EnableJpaRepositories("com.idea2strategy.trading.persistence")
public class TradingWorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(TradingWorkerApplication.class, args);
    }
}
