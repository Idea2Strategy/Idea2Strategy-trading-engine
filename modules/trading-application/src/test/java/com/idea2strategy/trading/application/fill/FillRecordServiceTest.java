package com.idea2strategy.trading.application.fill;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.idea2strategy.trading.domain.fill.FillAllocation;
import com.idea2strategy.trading.domain.fill.FillPosting;
import com.idea2strategy.trading.domain.fill.FillRecord;
import com.idea2strategy.trading.domain.order.OrderScope;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FillRecordServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-02T00:00:00Z");
    private static final UUID ORDER = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID COMPONENT = UUID.fromString("20000000-0000-0000-0000-000000000002");

    @Test
    void delegatesTheImmutablePostingToTheAtomicStore() {
        FillPosting posting = posting();

        FillRecordService service = new FillRecordService(requested -> requested.record());

        assertSame(posting.record(), service.record(posting));
    }

    @Test
    void rejectsNullInputsAndNullStoreResults() {
        assertThrows(IllegalArgumentException.class, () -> new FillRecordService(null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new FillRecordService(requested -> requested.record()).record(null));
        assertThrows(
                IllegalStateException.class,
                () -> new FillRecordService(requested -> null).record(posting()));
    }

    private static FillPosting posting() {
        FillRecord record = FillRecord.original(
                ORDER, "execution-1", BigDecimal.ONE, new BigDecimal("10"),
                new BigDecimal("0.02"), new BigDecimal("0.005"), NOW, NOW);
        return new FillPosting(
                record,
                new OrderScope(
                        UUID.fromString("30000000-0000-0000-0000-000000000003"),
                        UUID.fromString("40000000-0000-0000-0000-000000000004")),
                UUID.fromString("50000000-0000-0000-0000-000000000005"),
                UUID.fromString("60000000-0000-0000-0000-000000000006"),
                20,
                "precision-rules:v1",
                new BigDecimal("9.995"),
                NOW,
                "m".repeat(64),
                new BigDecimal("10"),
                new BigDecimal("10"),
                new BigDecimal("-10.02"),
                "fill-allocation:v1",
                List.of(new FillAllocation(
                        COMPONENT, 1, BigDecimal.ONE, new BigDecimal("10"),
                        new BigDecimal("0.02"), new BigDecimal("-10.02"))));
    }
}
