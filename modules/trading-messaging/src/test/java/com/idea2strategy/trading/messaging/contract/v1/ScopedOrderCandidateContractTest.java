package com.idea2strategy.trading.messaging.contract.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.idea2strategy.trading.messaging.evaluation.OrderCandidate;
import com.idea2strategy.trading.messaging.evaluation.OrderCandidateBatch;
import com.idea2strategy.trading.messaging.evaluation.OrderSide;
import com.idea2strategy.trading.messaging.fixture.v1.ContractJsonFixtureLoaderV1;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The order candidate contract has to carry the partition isolation scope before any canonical
 * order intent can be written.
 *
 * <p>{@code trading.order_intent_batches} requires {@code bot_id}, {@code partition_id} and
 * {@code source_event_id} NOT NULL, and {@code trading.order_intents} additionally requires
 * {@code flow_id} NOT NULL under a composite foreign key to {@code bot.flows}. Version 1 of this
 * contract carries none of them, so it stays readable but is not canonical ready.
 */
class ScopedOrderCandidateContractTest {

    @Test
    void theVersionTwoFixtureCarriesEverythingACanonicalIntentNeeds() {
        var batch = ContractJsonFixtureLoaderV1.readResource(
                "contracts/v2/order-candidate-batch.json",
                new TypeReference<OrderCandidateBatch>() {});

        assertThat(batch.schemaVersion()).isEqualTo(OrderCandidateBatch.SCOPED_SCHEMA_VERSION);
        assertThat(batch.carriesPartitionScope()).isTrue();
        assertThat(batch.bot()).contains(UUID.fromString("e332fd66-3a21-4d3e-8a2a-4c2e4ee55430"));
        assertThat(batch.partition()).contains(UUID.fromString("1f0a5b6c-8d2e-4a71-9c33-2b5e7d901aa4"));
        assertThat(batch.sourceEvent()).contains(UUID.fromString("7c1d2e3f-4a5b-4c6d-8e9f-0a1b2c3d4e5f"));
        assertThat(batch.candidates()).singleElement().satisfies(candidate ->
                assertThat(candidate.flow())
                        .contains(UUID.fromString("9b8a7c6d-5e4f-4a3b-9c2d-1e0f9a8b7c6d")));
    }

    @Test
    void theVersionOneFixtureStillDeserialisesButIsNotCanonicalReady() {
        var batch = ContractJsonFixtureLoaderV1.readResource(
                "contracts/v1/order-candidate-batch.json",
                new TypeReference<OrderCandidateBatch>() {});

        assertThat(batch.schemaVersion()).isEqualTo(1);
        assertThat(batch.carriesPartitionScope()).isFalse();
        assertThat(batch.bot()).isEmpty();
        assertThat(batch.partition()).isEmpty();
        assertThat(batch.sourceEvent()).isEmpty();
        assertThat(batch.candidates()).singleElement()
                .satisfies(candidate -> assertThat(candidate.flow()).isEmpty());
    }

    @Test
    void aScopedBatchWithAnUnscopedCandidateIsRefused() {
        assertThatThrownBy(() -> new OrderCandidateBatch(
                2, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), Instant.parse("2026-08-02T09:00:00Z"),
                List.of(unscopedCandidate())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("has no flowId");
    }

    @Test
    void aScopedBatchMissingAnyOneScopeFieldIsRefused() {
        UUID id = UUID.randomUUID();
        Instant at = Instant.parse("2026-08-02T09:00:00Z");
        for (String missing : List.of("botId", "partitionId", "sourceEventId")) {
            assertThatThrownBy(() -> new OrderCandidateBatch(
                    2, id, id,
                    "botId".equals(missing) ? null : id,
                    "partitionId".equals(missing) ? null : id,
                    "sourceEventId".equals(missing) ? null : id,
                    at, List.of(scopedCandidate())))
                    .as("missing %s", missing)
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining(missing);
        }
    }

    @Test
    void aVersionOneBatchCarryingAPartialScopeIsRefused() {
        // Half a scope is worse than none: it would look canonical ready while one NOT NULL column
        // still has nothing to fill it.
        assertThatThrownBy(() -> new OrderCandidateBatch(
                1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, null,
                Instant.parse("2026-08-02T09:00:00Z"), List.of(unscopedCandidate())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not carry a partition scope");
    }

    private static OrderCandidate scopedCandidate() {
        return new OrderCandidate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                OrderSide.BUY, BigDecimal.ONE, null, List.of("BASIC_RULE_MATCHED"));
    }

    private static OrderCandidate unscopedCandidate() {
        return new OrderCandidate(UUID.randomUUID(), UUID.randomUUID(), OrderSide.BUY,
                BigDecimal.ONE, null, List.of("BASIC_RULE_MATCHED"));
    }
}
