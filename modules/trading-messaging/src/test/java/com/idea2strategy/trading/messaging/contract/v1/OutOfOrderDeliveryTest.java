package com.idea2strategy.trading.messaging.contract.v1;

import com.idea2strategy.trading.messaging.fixture.v1.ContractFixturesV1;
import com.idea2strategy.trading.messaging.fixture.v1.FixtureDeliveryProjectionV1;
import org.junit.jupiter.api.Test;

import static com.idea2strategy.trading.messaging.fixture.v1.FixtureDeliveryProjectionV1.DeliveryResult.APPLIED;
import static com.idea2strategy.trading.messaging.fixture.v1.FixtureDeliveryProjectionV1.DeliveryResult.STALE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutOfOrderDeliveryTest {

    @Test
    void staleIsIgnoredAndFutureGapIsRejected() {
        var projection = new FixtureDeliveryProjectionV1();

        assertThat(projection.accept(ContractFixturesV1.acceptedEnvelope())).isEqualTo(APPLIED);
        assertThat(projection.accept(ContractFixturesV1.staleAcceptedEnvelope())).isEqualTo(STALE);
        assertThatThrownBy(() -> projection.accept(ContractFixturesV1.futureGapEnvelope()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("sequence gap");
    }
}
