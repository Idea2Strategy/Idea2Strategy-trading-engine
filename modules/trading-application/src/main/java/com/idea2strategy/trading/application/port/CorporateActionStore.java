package com.idea2strategy.trading.application.port;

import com.idea2strategy.trading.application.corporateaction.CorporateActionApplicationResult;
import com.idea2strategy.trading.domain.corporateaction.CorporateActionApplication;

public interface CorporateActionStore {

    /** Applies one approved corporate action to the lots a single bot holds in the instrument. */
    CorporateActionApplicationResult apply(CorporateActionApplication application);
}
