package com.idea2strategy.trading.worker.reservation;

import com.idea2strategy.trading.application.port.ResourceReservationStore;
import com.idea2strategy.trading.application.reservation.ResourceReservationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ResourceReservationConfiguration {
    @Bean
    ResourceReservationService resourceReservationService(ResourceReservationStore store) {
        return new ResourceReservationService(store);
    }
}
