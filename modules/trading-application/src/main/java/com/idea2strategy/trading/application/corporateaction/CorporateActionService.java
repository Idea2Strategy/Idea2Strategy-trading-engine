package com.idea2strategy.trading.application.corporateaction;
import com.idea2strategy.trading.application.port.CorporateActionStore;
import com.idea2strategy.trading.domain.corporateaction.ApprovedCorporateAction;
public final class CorporateActionService{private final CorporateActionStore store;public CorporateActionService(CorporateActionStore store){if(store==null)throw new IllegalArgumentException("store must not be null");this.store=store;}public CorporateActionApplicationResult apply(ApprovedCorporateAction action){return store.apply(action);}}
