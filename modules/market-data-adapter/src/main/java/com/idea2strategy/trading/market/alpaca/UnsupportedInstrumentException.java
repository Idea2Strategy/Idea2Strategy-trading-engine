package com.idea2strategy.trading.market.alpaca;

public final class UnsupportedInstrumentException extends IllegalArgumentException {
    public static final String REASON_CODE = "UNSUPPORTED_INSTRUMENT";

    private final String symbol;

    public UnsupportedInstrumentException(String symbol) {
        super(REASON_CODE + ": " + symbol);
        this.symbol = symbol;
    }

    public String reasonCode() {
        return REASON_CODE;
    }

    public String symbol() {
        return symbol;
    }
}
