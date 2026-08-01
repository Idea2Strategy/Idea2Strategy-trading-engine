package com.idea2strategy.trading.strategy.runtime.basic;

public record EqualAllocationShare(int numerator, int denominator) {
    public EqualAllocationShare {
        if (numerator != 1 || denominator < 1) {
            throw new IllegalArgumentException("an equal allocation share must be the exact fraction 1/N");
        }
    }
}
