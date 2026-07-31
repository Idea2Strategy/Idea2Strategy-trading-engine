package com.idea2strategy.trading.messaging.contract.v1;

import com.idea2strategy.trading.messaging.fixture.v1.ContractFixturesV1;
import com.idea2strategy.trading.messaging.fixture.v1.FixtureDeliveryProjectionV1;
import com.idea2strategy.trading.messaging.fixture.v1.FixtureDeliveryProjectionV1.DeliveryResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DuplicatePartialFillDeliveryTest {

    @Test
    void duplicatePartialFillCreatesOneTradeAndOneLedgerPostingSet() {
        var partialFill = ContractFixturesV1.partialFillEnvelope();
        var projection = new FixtureDeliveryProjectionV1();

        assertThat(projection.accept(partialFill)).isEqualTo(DeliveryResult.APPLIED);
        assertThat(projection.accept(partialFill)).isEqualTo(DeliveryResult.DUPLICATE);
        assertThat(projection.tradeCount()).isEqualTo(1);
        assertThat(projection.ledgerEntryCount()).isEqualTo(
            partialFill.payload().ledgerTransaction().entries().size()
        );
    }
}
