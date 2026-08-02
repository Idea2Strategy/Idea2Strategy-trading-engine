package com.idea2strategy.trading.application.corporateaction;

import com.idea2strategy.trading.application.port.CorporateActionStore;
import com.idea2strategy.trading.domain.corporateaction.CorporateActionApplication;
import java.util.Objects;

public final class CorporateActionService {

    private final CorporateActionStore store;

    public CorporateActionService(CorporateActionStore store) {
        this.store = Objects.requireNonNull(store, "store must not be null");
    }

    public CorporateActionApplicationResult apply(CorporateActionApplication application) {
        return store.apply(Objects.requireNonNull(application, "application must not be null"));
    }
}
