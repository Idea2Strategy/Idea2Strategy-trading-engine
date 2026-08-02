package com.idea2strategy.trading.application.port;

import com.idea2strategy.trading.domain.policy.EffectiveTradingPolicy;
import java.time.Instant;

/**
 * Resolves the platform policy version that was in force at a moment.
 *
 * <p>This is read only on purpose. The published policy rows are platform product data: the
 * canonical model fixes the fee at 20 bps with a CHECK, but leaves {@code buffer_bps} and the short
 * risk {@code rules_document} open, so this service reads whichever version is published rather
 * than choosing a value. A write path that cannot resolve a policy fails loudly instead of
 * inventing one.
 */
public interface TradingPolicyRegistry {

    EffectiveTradingPolicy.Fee feePolicyAt(Instant at);

    EffectiveTradingPolicy.BuyingPowerBuffer buyingPowerBufferPolicyAt(Instant at);

    EffectiveTradingPolicy.ShortRisk shortRiskPolicyAt(Instant at);

    EffectiveTradingPolicy.ShortBorrowFee shortBorrowFeePolicyAt(Instant at);
}
