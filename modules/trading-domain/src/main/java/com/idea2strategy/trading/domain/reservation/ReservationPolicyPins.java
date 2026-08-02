package com.idea2strategy.trading.domain.reservation;

import java.util.UUID;

/**
 * The platform rules a reservation is fixed against.
 *
 * <p>Canonical demands a different pin set per resource type and forbids the wrong ones:
 * {@code cash_reservation_evidence_required} wants a buffer and a fee policy on buying power,
 * {@code short_collateral_policy_required} wants a short-risk policy on collateral, and
 * {@code cash_policies_only_for_buying_power} and {@code short_policy_only_for_collateral} reject a
 * reservation that carries a pin its resource type has no use for. Checking that here means a
 * mispinned reservation fails at the boundary that built it instead of inside the database.
 */
public record ReservationPolicyPins(
        UUID bufferPolicyId,
        UUID feePolicyId,
        UUID shortRiskPolicyId,
        String precisionRulesVersion) {

    public ReservationPolicyPins {
        precisionRulesVersion =
                ReservationValues.nonBlank(precisionRulesVersion, "precisionRulesVersion");
    }

    /** Buying power: pinned to the buffer and fee policies that sized the reservation. */
    public static ReservationPolicyPins buyingPower(
            UUID bufferPolicyId, UUID feePolicyId, String precisionRulesVersion) {
        return new ReservationPolicyPins(
                ReservationValues.required(bufferPolicyId, "bufferPolicyId"),
                ReservationValues.required(feePolicyId, "feePolicyId"), null,
                precisionRulesVersion);
    }

    /** Position quantity: no cash policy applies, so canonical forbids pinning one. */
    public static ReservationPolicyPins positionQuantity(String precisionRulesVersion) {
        return new ReservationPolicyPins(null, null, null, precisionRulesVersion);
    }

    /** Short collateral: pinned to the short-risk policy that sized the collateral. */
    public static ReservationPolicyPins shortCollateral(
            UUID shortRiskPolicyId, String precisionRulesVersion) {
        return new ReservationPolicyPins(
                null, null, ReservationValues.required(shortRiskPolicyId, "shortRiskPolicyId"),
                precisionRulesVersion);
    }

    void requireFits(ReservationResourceType resourceType) {
        boolean cash = resourceType == ReservationResourceType.CASH_BUYING_POWER;
        boolean collateral = resourceType == ReservationResourceType.SHORT_COLLATERAL_CASH;
        if (cash != (bufferPolicyId != null) || cash != (feePolicyId != null)) {
            throw new IllegalArgumentException(
                    "buffer and fee policies belong to a buying power reservation only");
        }
        if (collateral != (shortRiskPolicyId != null)) {
            throw new IllegalArgumentException(
                    "a short risk policy belongs to a short collateral reservation only");
        }
    }
}
