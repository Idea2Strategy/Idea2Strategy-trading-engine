package com.idea2strategy.trading.market.alpaca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class EnvironmentAlpacaCredentialsProviderTest {
    @Test
    void readsCredentialsOnlyFromNamedEnvironmentVariables() {
        Map<String, String> environment = Map.of(
                EnvironmentAlpacaCredentialsProvider.API_KEY_VARIABLE, "key-from-secret-store",
                EnvironmentAlpacaCredentialsProvider.API_SECRET_VARIABLE, "secret-from-secret-store");

        AlpacaCredentials credentials = new EnvironmentAlpacaCredentialsProvider(environment::get).load();

        assertEquals("key-from-secret-store", credentials.apiKey());
        assertEquals("secret-from-secret-store", credentials.apiSecret());
    }

    @Test
    void refusesToStartWhenEitherCredentialIsAbsent() {
        EnvironmentAlpacaCredentialsProvider provider = new EnvironmentAlpacaCredentialsProvider(
                name -> EnvironmentAlpacaCredentialsProvider.API_KEY_VARIABLE.equals(name) ? "key" : null);

        assertThrows(IllegalStateException.class, provider::load);
    }
}
