package com.idea2strategy.trading.application.corporateaction;
import java.util.UUID;
public record CorporateActionApplicationResult(UUID actionId,int adjustedLots,int adjustedFlowPositions,String ledgerEffect){}
