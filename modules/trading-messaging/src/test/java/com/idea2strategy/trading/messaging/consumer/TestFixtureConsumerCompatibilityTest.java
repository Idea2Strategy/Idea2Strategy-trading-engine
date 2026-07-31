package com.idea2strategy.trading.messaging.consumer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.idea2strategy.trading.messaging.contract.v1.OrderExecutionContractV1;
import com.idea2strategy.trading.messaging.contract.v1.TradingEnvelopeV1;
import com.idea2strategy.trading.messaging.evaluation.OrderCandidateBatch;
import com.idea2strategy.trading.messaging.fixture.v1.ContractJsonFixtureLoaderV1;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestFixtureConsumerCompatibilityTest {

    @Test
    void externalPackageCompilesAgainstPublicJacksonSignaturesAndLoadsBothResourceFamilies() {
        OrderCandidateBatch candidateBatch = ContractJsonFixtureLoaderV1.readResource(
            "contracts/v1/order-candidate-batch.json", new TypeReference<OrderCandidateBatch>() {}
        );
        TradingEnvelopeV1<OrderExecutionContractV1.IntentBatch> intentBatch = ContractJsonFixtureLoaderV1.read(
            "intent-batch.json", new TypeReference<TradingEnvelopeV1<OrderExecutionContractV1.IntentBatch>>() {}
        );

        assertThat(candidateBatch.candidates()).isNotEmpty();
        assertThat(intentBatch.payload().intents()).isNotEmpty();
    }
}
