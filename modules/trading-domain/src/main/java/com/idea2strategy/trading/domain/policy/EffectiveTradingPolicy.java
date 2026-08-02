package com.idea2strategy.trading.domain.policy;

import java.util.Objects;
import java.util.UUID;

/**
 * The platform policy version that was in force at a moment.
 *
 * <p>Canonical trading writes cannot invent these. {@code trading.orders.fee_policy_id},
 * {@code trading.fills.fee_policy_id}, {@code trading.resource_reservations.buffer_policy_id},
 * {@code .fee_policy_id}, {@code .short_risk_policy_id} and
 * {@code trading.short_borrow_fee_accruals.short_borrow_fee_policy_id} are NOT NULL foreign keys
 * into the policy version tables, and the canonical note says a past fill pins the policy id that
 * was in force at the time. So the write path resolves the version rather than deriving a rate.
 */
public sealed interface EffectiveTradingPolicy {

    /** The value stored in the canonical foreign key. */
    UUID policyVersionId();

    /** Stable policy identifier, independent of version. */
    String policyCode();

    /** The published version of that policy. */
    String version();

    /** Digest of the exact rules this version fixes. */
    String rulesHash();

    /**
     * The official trading fee. The canonical table pins {@code fee_rate_bps = 20} with a CHECK, so
     * the rate is read rather than chosen.
     */
    record Fee(UUID policyVersionId, String policyCode, String version, int feeRateBps,
               String calculationRulesVersion, String rulesHash) implements EffectiveTradingPolicy {
        public Fee {
            requireCommon(policyVersionId, policyCode, version, rulesHash);
            Objects.requireNonNull(calculationRulesVersion, "calculationRulesVersion");
            if (feeRateBps < 0) {
                throw new IllegalArgumentException("feeRateBps must not be negative");
            }
        }
    }

    /**
     * The buying power buffer. Canonical only requires {@code buffer_bps >= 0}; the actual value is
     * product data, so it is always read from the published version.
     */
    record BuyingPowerBuffer(UUID policyVersionId, String policyCode, String version, int bufferBps,
                             String roundingRulesVersion, String rulesHash)
            implements EffectiveTradingPolicy {
        public BuyingPowerBuffer {
            requireCommon(policyVersionId, policyCode, version, rulesHash);
            Objects.requireNonNull(roundingRulesVersion, "roundingRulesVersion");
            if (bufferBps < 0) {
                throw new IllegalArgumentException("bufferBps must not be negative");
            }
        }
    }

    /** The short risk rules. The rules themselves are an opaque published document. */
    record ShortRisk(UUID policyVersionId, String policyCode, String version, String rulesDocument,
                     String rulesHash) implements EffectiveTradingPolicy {
        public ShortRisk {
            requireCommon(policyVersionId, policyCode, version, rulesHash);
            Objects.requireNonNull(rulesDocument, "rulesDocument");
        }
    }

    /** The short borrow fee. */
    record ShortBorrowFee(UUID policyVersionId, String policyCode, String version,
                          java.math.BigDecimal annualFeeRateBps, String dayCountBasis,
                          String calculationRulesVersion, String rulesHash)
            implements EffectiveTradingPolicy {
        public ShortBorrowFee {
            requireCommon(policyVersionId, policyCode, version, rulesHash);
            Objects.requireNonNull(annualFeeRateBps, "annualFeeRateBps");
            Objects.requireNonNull(dayCountBasis, "dayCountBasis");
            Objects.requireNonNull(calculationRulesVersion, "calculationRulesVersion");
            if (annualFeeRateBps.signum() < 0) {
                throw new IllegalArgumentException("annualFeeRateBps must not be negative");
            }
        }
    }

    private static void requireCommon(UUID id, String policyCode, String version, String rulesHash) {
        Objects.requireNonNull(id, "policyVersionId");
        Objects.requireNonNull(policyCode, "policyCode");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(rulesHash, "rulesHash");
    }
}
