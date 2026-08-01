package com.idea2strategy.trading.messaging.contract.v1;

import com.fasterxml.jackson.core.type.TypeReference;
import com.idea2strategy.trading.messaging.fixture.v1.ContractJsonFixtureLoaderV1;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderIntentValidationMatrixV1Test {

    @Test
    void canonicalMatrixExecutesEveryOrderTypeTimeInForceAndQuantityModeRule() {
        var scenarios = ContractJsonFixtureLoaderV1.readResource(
            "contracts/trading/v1/order-intent-validation-matrix.json",
            new TypeReference<List<Scenario>>() {}
        );

        assertThat(scenarios).extracting(Scenario::orderType)
            .containsAll(EnumSet.allOf(OrderExecutionContractV1.OrderType.class));
        assertThat(scenarios).extracting(Scenario::timeInForce)
            .containsAll(EnumSet.allOf(OrderExecutionContractV1.TimeInForce.class));
        assertThat(scenarios).extracting(Scenario::quantityMode)
            .containsAll(EnumSet.allOf(OrderExecutionContractV1.QuantityMode.class));
        assertThat(scenarios).extracting(Scenario::valid).contains(true, false);

        for (Scenario scenario : scenarios) {
            if (scenario.valid()) {
                assertThatCode(() -> intent(scenario)).as(scenario.name()).doesNotThrowAnyException();
            } else {
                assertThatThrownBy(() -> intent(scenario)).as(scenario.name())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(scenario.error());
            }
        }
    }

    private OrderExecutionContractV1.Intent intent(Scenario scenario) {
        return new OrderExecutionContractV1.Intent(
            UUID.nameUUIDFromBytes(("intent-" + scenario.name()).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            UUID.fromString("00000000-0000-0000-0000-000000000211"),
            UUID.fromString("00000000-0000-0000-0000-000000000104"),
            OrderExecutionContractV1.Side.BUY,
            scenario.orderType(),
            new OrderExecutionContractV1.OrderParameters(
                price(scenario.limitPrice()), price(scenario.stopPrice()), decimal(scenario.trailPercent())
            ),
            scenario.timeInForce(),
            scenario.expiresAt() == null ? null : Instant.parse(scenario.expiresAt()),
            scenario.quantityMode(),
            new DecimalValueV1("10"),
            new DecimalValueV1("10"),
            OrderExecutionContractV1.IntentDecision.ACCEPTED,
            null,
            new OrderExecutionContractV1.CostPolicy(
                "virtual-fill-cost-v1", new DecimalValueV1("0.002"), new DecimalValueV1("0.0005")
            )
        );
    }

    private CurrencyAmountV1 price(String value) {
        return value == null ? null : new CurrencyAmountV1("USD", new DecimalValueV1(value));
    }

    private DecimalValueV1 decimal(String value) {
        return value == null ? null : new DecimalValueV1(value);
    }

    private record Scenario(
        String name,
        OrderExecutionContractV1.OrderType orderType,
        OrderExecutionContractV1.TimeInForce timeInForce,
        OrderExecutionContractV1.QuantityMode quantityMode,
        String limitPrice,
        String stopPrice,
        String trailPercent,
        String expiresAt,
        boolean valid,
        String error
    ) {
    }
}
