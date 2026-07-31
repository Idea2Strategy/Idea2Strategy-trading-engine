package com.idea2strategy.trading.messaging.contract.v1;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderExecutionContractV1Test {

    @Test
    void acceptsEligibleFractionalMarketDayLongIntent() {
        assertThatCode(this::validFractionalMarketDayIntent).doesNotThrowAnyException();
    }

    @Test
    void rejectsFractionalLimitIntent() {
        assertThatThrownBy(this::fractionalLimitIntent)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("fractional");
    }

    @Test
    void rejectsNotionalShortIntent() {
        assertThatThrownBy(this::notionalShortIntent)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("short");
    }

    @Test
    void requiresExpiryForGtdIntent() {
        assertThatThrownBy(this::gtdIntentWithoutExpiry)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("expiry");
    }

    @Test
    void rejectsExpiryForDayAndGtcIntents() {
        assertThatThrownBy(() -> intent(
            OrderExecutionContractV1.Side.BUY,
            OrderExecutionContractV1.OrderType.MARKET,
            OrderExecutionContractV1.TimeInForce.DAY,
            Instant.parse("2026-08-01T00:00:00Z"),
            OrderExecutionContractV1.QuantityMode.WHOLE_SHARES
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("expiry");

        assertThatThrownBy(() -> intent(
            OrderExecutionContractV1.Side.BUY,
            OrderExecutionContractV1.OrderType.MARKET,
            OrderExecutionContractV1.TimeInForce.GTC,
            Instant.parse("2026-08-01T00:00:00Z"),
            OrderExecutionContractV1.QuantityMode.WHOLE_SHARES
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("expiry");
    }

    @Test
    void requiresExactVersionedCostPolicyRates() {
        assertThatThrownBy(() -> new OrderExecutionContractV1.CostPolicy(
            "cost-policy-v1",
            new DecimalValueV1("0.0021"),
            new DecimalValueV1("0.0005")
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("feeRate");

        assertThatThrownBy(() -> new OrderExecutionContractV1.CostPolicy(
            " ",
            new DecimalValueV1("0.002"),
            new DecimalValueV1("0.0005")
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("version");
    }

    @Test
    void rejectsDuplicateIntentIdsInBatchAndDefensivelyCopiesIntents() {
        var orderIntent = validFractionalMarketDayIntent();
        var intents = new java.util.ArrayList<>(List.of(orderIntent));
        var batch = new OrderExecutionContractV1.IntentBatch(
            id("11111111-1111-1111-1111-111111111111"),
            id("22222222-2222-2222-2222-222222222222"),
            id("33333333-3333-3333-3333-333333333333"),
            intents
        );

        intents.clear();

        assertThat(batch.intents()).containsExactly(orderIntent);
        assertThatThrownBy(() -> new OrderExecutionContractV1.IntentBatch(
            id("11111111-1111-1111-1111-111111111111"),
            id("22222222-2222-2222-2222-222222222222"),
            id("33333333-3333-3333-3333-333333333333"),
            List.of(orderIntent, orderIntent)
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("duplicate intentId");
    }

    @Test
    void rejectsFractionalRequestedQuantityForWholeShareShortAndNonMarketOrders() {
        for (var orderType : OrderExecutionContractV1.OrderType.values()) {
            var side = orderType == OrderExecutionContractV1.OrderType.MARKET
                ? OrderExecutionContractV1.Side.SELL_SHORT
                : OrderExecutionContractV1.Side.BUY;

            assertThatThrownBy(() -> wholeShareIntent(
                side,
                orderType,
                "10.5",
                "0",
                OrderExecutionContractV1.IntentDecision.REJECTED,
                "QUANTITY_NOT_SUPPORTED"
            )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole shares");
        }
    }

    @Test
    void rejectsFractionalApprovedQuantityForWholeShareShortAndNonMarketOrders() {
        for (var orderType : OrderExecutionContractV1.OrderType.values()) {
            var side = orderType == OrderExecutionContractV1.OrderType.MARKET
                ? OrderExecutionContractV1.Side.SELL_SHORT
                : OrderExecutionContractV1.Side.BUY;

            assertThatThrownBy(() -> wholeShareIntent(
                side,
                orderType,
                "11",
                "10.5",
                OrderExecutionContractV1.IntentDecision.REDUCED,
                "RISK_LIMIT"
            )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole shares");
        }
    }

    @Test
    void acceptsValidDecisionQuantityAndReasonCombinations() {
        assertThatCode(() -> wholeShareIntent(
            OrderExecutionContractV1.Side.BUY,
            OrderExecutionContractV1.OrderType.MARKET,
            "10",
            "10",
            OrderExecutionContractV1.IntentDecision.ACCEPTED,
            null
        )).doesNotThrowAnyException();
        assertThatCode(() -> wholeShareIntent(
            OrderExecutionContractV1.Side.BUY,
            OrderExecutionContractV1.OrderType.MARKET,
            "10",
            "5",
            OrderExecutionContractV1.IntentDecision.REDUCED,
            "RISK_LIMIT"
        )).doesNotThrowAnyException();
        assertThatCode(() -> wholeShareIntent(
            OrderExecutionContractV1.Side.BUY,
            OrderExecutionContractV1.OrderType.MARKET,
            "10",
            "0",
            OrderExecutionContractV1.IntentDecision.REJECTED,
            "MARKET_CLOSED"
        )).doesNotThrowAnyException();
    }

    @Test
    void rejectsInvalidDecisionQuantityAndReasonCombinations() {
        assertThatThrownBy(() -> wholeShareIntent(
            OrderExecutionContractV1.Side.BUY,
            OrderExecutionContractV1.OrderType.MARKET,
            "10",
            "9",
            OrderExecutionContractV1.IntentDecision.ACCEPTED,
            null
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ACCEPTED");
        assertThatThrownBy(() -> wholeShareIntent(
            OrderExecutionContractV1.Side.BUY,
            OrderExecutionContractV1.OrderType.MARKET,
            "10",
            "0",
            OrderExecutionContractV1.IntentDecision.REDUCED,
            "RISK_LIMIT"
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("REDUCED");
        assertThatThrownBy(() -> wholeShareIntent(
            OrderExecutionContractV1.Side.BUY,
            OrderExecutionContractV1.OrderType.MARKET,
            "10",
            "5",
            OrderExecutionContractV1.IntentDecision.REDUCED,
            " "
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("reasonCode");
        assertThatThrownBy(() -> wholeShareIntent(
            OrderExecutionContractV1.Side.BUY,
            OrderExecutionContractV1.OrderType.MARKET,
            "10",
            "1",
            OrderExecutionContractV1.IntentDecision.REJECTED,
            "MARKET_CLOSED"
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("REJECTED");
        assertThatThrownBy(() -> wholeShareIntent(
            OrderExecutionContractV1.Side.BUY,
            OrderExecutionContractV1.OrderType.MARKET,
            "10",
            "0",
            OrderExecutionContractV1.IntentDecision.REJECTED,
            null
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("reasonCode");
    }

    @Test
    void rejectsNullIntentElementsBeforeCopyingBatchIntents() {
        assertThatThrownBy(() -> new OrderExecutionContractV1.IntentBatch(
            id("11111111-1111-1111-1111-111111111111"),
            id("22222222-2222-2222-2222-222222222222"),
            id("33333333-3333-3333-3333-333333333333"),
            Arrays.asList(validWholeShareIntent(), null)
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("intent");
    }

    private OrderExecutionContractV1.Intent validFractionalMarketDayIntent() {
        return intent(
            OrderExecutionContractV1.Side.BUY,
            OrderExecutionContractV1.OrderType.MARKET,
            OrderExecutionContractV1.TimeInForce.DAY,
            null,
            OrderExecutionContractV1.QuantityMode.FRACTIONAL_SHARES
        );
    }

    private OrderExecutionContractV1.Intent fractionalLimitIntent() {
        return intent(
            OrderExecutionContractV1.Side.BUY,
            OrderExecutionContractV1.OrderType.LIMIT,
            OrderExecutionContractV1.TimeInForce.DAY,
            null,
            OrderExecutionContractV1.QuantityMode.FRACTIONAL_SHARES
        );
    }

    private OrderExecutionContractV1.Intent notionalShortIntent() {
        return intent(
            OrderExecutionContractV1.Side.SELL_SHORT,
            OrderExecutionContractV1.OrderType.MARKET,
            OrderExecutionContractV1.TimeInForce.DAY,
            null,
            OrderExecutionContractV1.QuantityMode.NOTIONAL_AMOUNT
        );
    }

    private OrderExecutionContractV1.Intent gtdIntentWithoutExpiry() {
        return intent(
            OrderExecutionContractV1.Side.BUY,
            OrderExecutionContractV1.OrderType.MARKET,
            OrderExecutionContractV1.TimeInForce.GTD,
            null,
            OrderExecutionContractV1.QuantityMode.WHOLE_SHARES
        );
    }

    private OrderExecutionContractV1.Intent validWholeShareIntent() {
        return wholeShareIntent(
            OrderExecutionContractV1.Side.BUY,
            OrderExecutionContractV1.OrderType.MARKET,
            "10",
            "10",
            OrderExecutionContractV1.IntentDecision.ACCEPTED,
            null
        );
    }

    private OrderExecutionContractV1.Intent wholeShareIntent(
        OrderExecutionContractV1.Side side,
        OrderExecutionContractV1.OrderType orderType,
        String requestedQuantity,
        String approvedQuantity,
        OrderExecutionContractV1.IntentDecision decision,
        String reasonCode
    ) {
        return new OrderExecutionContractV1.Intent(
            id("44444444-4444-4444-4444-444444444444"),
            id("55555555-5555-5555-5555-555555555555"),
            id("66666666-6666-6666-6666-666666666666"),
            side,
            orderType,
            OrderExecutionContractV1.TimeInForce.DAY,
            null,
            OrderExecutionContractV1.QuantityMode.WHOLE_SHARES,
            new DecimalValueV1(requestedQuantity),
            new DecimalValueV1(approvedQuantity),
            decision,
            reasonCode,
            new OrderExecutionContractV1.CostPolicy(
                "cost-policy-v1",
                new DecimalValueV1("0.002"),
                new DecimalValueV1("0.0005")
            )
        );
    }

    private OrderExecutionContractV1.Intent intent(
        OrderExecutionContractV1.Side side,
        OrderExecutionContractV1.OrderType orderType,
        OrderExecutionContractV1.TimeInForce timeInForce,
        Instant expiresAt,
        OrderExecutionContractV1.QuantityMode quantityMode
    ) {
        return new OrderExecutionContractV1.Intent(
            id("44444444-4444-4444-4444-444444444444"),
            id("55555555-5555-5555-5555-555555555555"),
            id("66666666-6666-6666-6666-666666666666"),
            side,
            orderType,
            timeInForce,
            expiresAt,
            quantityMode,
            new DecimalValueV1("10.5"),
            new DecimalValueV1("10.5"),
            OrderExecutionContractV1.IntentDecision.ACCEPTED,
            null,
            new OrderExecutionContractV1.CostPolicy(
                "cost-policy-v1",
                new DecimalValueV1("0.002"),
                new DecimalValueV1("0.0005")
            )
        );
    }

    private UUID id(String value) {
        return UUID.fromString(value);
    }
}
