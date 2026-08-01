package com.idea2strategy.trading.market.alpaca;

import java.util.Objects;
import java.util.function.Function;

public final class EnvironmentAlpacaCredentialsProvider implements AlpacaCredentialsProvider {
    public static final String API_KEY_VARIABLE = "ALPACA_API_KEY";
    public static final String API_SECRET_VARIABLE = "ALPACA_API_SECRET";

    private final Function<String, String> environment;

    public EnvironmentAlpacaCredentialsProvider(Function<String, String> environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    public static EnvironmentAlpacaCredentialsProvider systemEnvironment() {
        return new EnvironmentAlpacaCredentialsProvider(System::getenv);
    }

    @Override
    public AlpacaCredentials load() {
        return new AlpacaCredentials(
                requireSecret(API_KEY_VARIABLE),
                requireSecret(API_SECRET_VARIABLE));
    }

    private String requireSecret(String variable) {
        String value = environment.apply(variable);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(variable + " must be supplied by the environment or secret provider");
        }
        return value;
    }
}
