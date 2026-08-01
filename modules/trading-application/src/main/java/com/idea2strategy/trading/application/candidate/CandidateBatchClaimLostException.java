package com.idea2strategy.trading.application.candidate;

public class CandidateBatchClaimLostException extends IllegalStateException {
    public CandidateBatchClaimLostException(CandidateBatchClaim claim) {
        super("Candidate batch claim is no longer active: " + claim.batchId());
    }
}
