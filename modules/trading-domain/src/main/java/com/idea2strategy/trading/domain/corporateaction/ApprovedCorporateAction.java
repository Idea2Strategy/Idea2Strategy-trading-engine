package com.idea2strategy.trading.domain.corporateaction;

import java.time.Instant;
import java.util.UUID;

public record ApprovedCorporateAction(UUID actionId, UUID instrumentId, CorporateActionType type,
                                      long numerator, long denominator, Instant effectiveAt,
                                      CorporateActionApprovalStatus approvalStatus, UUID approvalId,
                                      UUID approvedByOperatorId, Instant approvedAt,
                                      String evidenceDigest, String policyVersion) {
    public ApprovedCorporateAction {
        if(actionId==null||instrumentId==null||type==null||effectiveAt==null||approvalStatus==null||approvalId==null
                ||approvedByOperatorId==null||approvedAt==null)throw new IllegalArgumentException("corporate action values must not be null");
        if(approvalStatus!=CorporateActionApprovalStatus.APPROVED)throw new IllegalArgumentException("only approved corporate actions may be applied");
        if(numerator<=0||denominator<=0)throw new IllegalArgumentException("split ratio must be positive");
        if(approvedAt.isAfter(effectiveAt))throw new IllegalArgumentException("approval must precede effective time");
        if(evidenceDigest==null||!evidenceDigest.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("evidenceDigest must be sha256 hex");
        if(policyVersion==null||policyVersion.isBlank())throw new IllegalArgumentException("policyVersion must not be blank");
    }
}
