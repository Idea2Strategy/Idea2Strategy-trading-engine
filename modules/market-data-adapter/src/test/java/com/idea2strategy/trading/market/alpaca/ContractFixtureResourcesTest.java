package com.idea2strategy.trading.market.alpaca;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ContractFixtureResourcesTest {
    @Test
    void packagesMarketEventEdgeCasesWithRequiredEnvelopeFields() throws IOException {
        var fixture = resource("contracts/v1/market-event-edge-cases.json");

        assertContains(
                fixture,
                "\"eventId\"",
                "\"schemaVersion\"",
                "\"instrumentId\"",
                "\"provider\"",
                "\"feed\"",
                "\"occurredAt\"",
                "\"receivedAt\"",
                "\"duplicate\"",
                "\"outOfOrder\"",
                "\"correction\"",
                "\"UNSUPPORTED_INSTRUMENT\"");
    }

    @Test
    void packagesProviderNeutralQuoteTradeAndThirtyMinuteBarExamples() throws IOException {
        var fixture = resource("contracts/v1/provider-neutral-market-events.json");

        assertContains(
                fixture,
                "\"QUOTE\"",
                "\"TRADE\"",
                "\"BAR_30M\"",
                "\"ALPACA\"",
                "\"SIP\"",
                "\"instrumentId\"",
                "\"providerEventId\"");
    }

    @Test
    void correlatesTheEvaluationResultAndCandidateBatch() throws IOException {
        var result = resource("contracts/v1/evaluation-result.json");
        var batch = resource("contracts/v1/order-candidate-batch.json");
        var evaluationId = "626825b7-9de7-447a-a775-d8840fd24e55";

        assertTrue(result.contains(evaluationId));
        assertTrue(batch.contains(evaluationId));
        assertContains(result, "\"triggeringEventId\"", "\"outcome\"", "\"reasonCodes\"");
        assertContains(batch, "\"batchId\"", "\"candidates\"", "\"candidateId\"", "\"instrumentId\"");
    }

    private static String resource(String path) throws IOException {
        try (var stream = ContractFixtureResourcesTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(stream, path + " must be packaged as a test fixture resource");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void assertContains(String value, String... expectedTokens) {
        for (var token : expectedTokens) {
            assertTrue(value.contains(token), () -> "fixture is missing " + token);
        }
    }
}
