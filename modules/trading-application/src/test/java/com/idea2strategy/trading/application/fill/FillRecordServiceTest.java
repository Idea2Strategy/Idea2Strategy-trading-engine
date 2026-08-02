package com.idea2strategy.trading.application.fill;

import static org.junit.jupiter.api.Assertions.assertSame;

import com.idea2strategy.trading.domain.fill.FillRecord;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FillRecordServiceTest {
    @Test
    void delegatesTheImmutableRecordToTheAtomicStore() {
        Instant now = Instant.parse("2026-08-02T00:00:00Z");
        FillRecord record = FillRecord.original(UUID.randomUUID(), "execution-1", BigDecimal.ONE,
                new BigDecimal("10"), new BigDecimal("0.02"), new BigDecimal("0.005"), now, now);
        FillRecordService service = new FillRecordService(desired -> desired);
        assertSame(record, service.record(record));
    }
}
