package com.idea2strategy.trading.market.alpaca;

public record MissingSequenceRange(long fromInclusive, long toInclusive) {
    public MissingSequenceRange {
        if (fromInclusive < 0 || toInclusive < fromInclusive) {
            throw new IllegalArgumentException("missing sequence range must be non-negative and ordered");
        }
    }
}
