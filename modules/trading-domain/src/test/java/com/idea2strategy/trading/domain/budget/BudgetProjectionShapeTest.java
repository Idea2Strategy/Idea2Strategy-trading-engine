package com.idea2strategy.trading.domain.budget;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The canonical shape a budget projection has to arrive in.
 *
 * <p>Everything proven here is shape: currency, scale, sign, ordering and identity. What the
 * amounts mean is not this type's business, so nothing here asserts a relationship between them.
 */
class BudgetProjectionShapeTest {

    private static final UUID BOT = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID PARTITION = UUID.fromString("31000000-0000-4000-8000-000000000001");
    private static final Instant AT = Instant.parse("2026-08-02T14:30:00Z");

    @Test
    void amountsAreHeldAtTheCanonicalScaleAndTheDigestCoversTheWholeRow() {
        BotBudgetProjection projection = bot("1000", 7);

        assertAll(
                () -> assertEquals(8, projection.availableCashAmount().scale()),
                () -> assertEquals("1000.00000000",
                        projection.availableCashAmount().toPlainString()),
                () -> assertEquals(BOT, projection.projectionId()),
                () -> assertEquals(64, projection.projectionHash().length()),
                () -> assertEquals(projection.projectionHash(), bot("1000", 7).projectionHash()),
                () -> assertNotEquals(projection.projectionHash(), bot("1000.00000001", 7)
                        .projectionHash()),
                () -> assertNotEquals(projection.projectionHash(), bot("1000", 8)
                        .projectionHash()));
    }

    /**
     * A bot row and a partition row that happen to carry the same figures must not hash alike;
     * canonical keys them in different tables and the digest has to keep them apart.
     */
    @Test
    void aBotDigestAndAPartitionDigestNeverCollide() {
        assertNotEquals(bot("1000", 7).projectionHash(), partition(PARTITION, "1000", 7)
                .projectionHash());
    }

    @Test
    void anAmountThatDoesNotFitTheCanonicalScaleIsRefusedRatherThanRounded() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> bot("1000.000000001", 7));

        assertTrue(failure.getMessage().contains("canonical scale"));
    }

    /** Canonical's {@code *_nonnegative} CHECKs, refused where the value was supplied. */
    @Test
    void aNegativeAmountIsRefusedAtTheBoundary() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new BotBudgetProjection(
                        BOT, "USD", new BigDecimal("1000"), new BigDecimal("-1"), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, AT, "VALUED", 7));

        assertTrue(failure.getMessage().contains("activeReservationAmount"));
    }

    /** Canonical's {@code partition_budget_cap_positive}. */
    @Test
    void aPartitionWithoutABudgetCapIsRefused() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new PartitionBudgetProjection(
                        PARTITION, BOT, "USD", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, AT, "VALUED", 7));

        assertTrue(failure.getMessage().contains("budgetCapAmount"));
    }

    @Test
    void theCurrencyAndTheValuationStatusHaveToFitTheirCanonicalColumns() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new BotBudgetProjection(
                        BOT, "usd", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, AT, "VALUED", 7)),
                () -> assertThrows(IllegalArgumentException.class, () -> new BotBudgetProjection(
                        BOT, "USD", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, AT, "V".repeat(31), 7)),
                () -> assertThrows(IllegalArgumentException.class, () -> new BotBudgetProjection(
                        BOT, "USD", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, AT, "valued", 7)),
                () -> assertThrows(IllegalArgumentException.class, () -> new BotBudgetProjection(
                        BOT, "USD", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, AT, "VALUED", 0)));
    }

    /** {@code timestamptz} keeps microseconds, so the digest is taken over what is stored. */
    @Test
    void theValuationInstantIsHeldAtThePrecisionCanonicalCanStore() {
        BotBudgetProjection nanos = new BotBudgetProjection(
                BOT, "USD", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, Instant.parse("2026-08-02T14:30:00.123456789Z"), "VALUED", 7);

        assertEquals(Instant.parse("2026-08-02T14:30:00.123456Z"), nanos.valuationAt());
    }

    @Test
    void aRebuildIsOneAnswerAboutOneBotAtOneEventSequence() {
        UUID other = UUID.fromString("31000000-0000-4000-8000-000000000002");
        BudgetProjectionRebuild rebuild = new BudgetProjectionRebuild(
                bot("1000", 7), List.of(partition(PARTITION, "600", 7), partition(other, "400", 7)));

        assertAll(
                () -> assertEquals(BOT, rebuild.botId()),
                () -> assertEquals(7, rebuild.lastEventSequence()),
                () -> assertEquals(2, rebuild.partitions().size()),
                () -> assertThrows(IllegalArgumentException.class, () -> new BudgetProjectionRebuild(
                        bot("1000", 7), List.of(partition(PARTITION, "600", 8))),
                        "a partition at another event sequence is not part of this rebuild"),
                () -> assertThrows(IllegalArgumentException.class, () -> new BudgetProjectionRebuild(
                        bot("1000", 7),
                        List.of(partition(PARTITION, "600", 7), partition(PARTITION, "400", 7))),
                        "a partition may appear once"),
                () -> assertThrows(IllegalArgumentException.class, () -> new BudgetProjectionRebuild(
                        bot("1000", 7),
                        List.of(new PartitionBudgetProjection(
                                PARTITION, UUID.fromString("30000000-0000-4000-8000-000000000009"),
                                "USD", new BigDecimal("600"), BigDecimal.ZERO, BigDecimal.ZERO,
                                BigDecimal.ZERO, BigDecimal.ZERO, AT, "VALUED", 7))),
                        "a partition of another bot is not part of this rebuild"));
    }

    private static BotBudgetProjection bot(String availableCash, long sequence) {
        return new BotBudgetProjection(
                BOT, "USD", new BigDecimal(availableCash), new BigDecimal("50"),
                new BigDecimal("200"), new BigDecimal("30"), new BigDecimal("15"), AT, "VALUED",
                sequence);
    }

    private static PartitionBudgetProjection partition(UUID id, String cap, long sequence) {
        return new PartitionBudgetProjection(
                id, BOT, "USD", new BigDecimal(cap), new BigDecimal("50"), new BigDecimal("200"),
                new BigDecimal("30"), new BigDecimal("15"), AT, "VALUED", sequence);
    }
}
