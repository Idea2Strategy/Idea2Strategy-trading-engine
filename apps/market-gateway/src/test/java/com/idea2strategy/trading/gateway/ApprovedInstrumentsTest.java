package com.idea2strategy.trading.gateway;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApprovedInstrumentsTest {
    @Test
    void rejectsADeploymentMappingBelowTheConfiguredMinimum() {
        Map<String, UUID> mapping = Map.of(
                "AAPL", UUID.fromString("00000000-0000-4000-8000-000000000001"),
                "SPY", UUID.fromString("00000000-0000-4000-8000-000000000002"));

        assertThrows(IllegalArgumentException.class, () -> new ApprovedInstruments(mapping, 500));
        assertDoesNotThrow(() -> new ApprovedInstruments(mapping, 2));
    }
}
