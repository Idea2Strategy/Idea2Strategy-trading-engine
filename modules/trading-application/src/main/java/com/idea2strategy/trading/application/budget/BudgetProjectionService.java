package com.idea2strategy.trading.application.budget;

import com.idea2strategy.trading.application.port.BudgetProjectionStore;
import com.idea2strategy.trading.domain.budget.BotBudgetProjection;
import com.idea2strategy.trading.domain.budget.BudgetProjectionRebuild;
import com.idea2strategy.trading.domain.budget.PartitionBudgetProjection;
import java.util.Objects;

/**
 * Publishes rebuilt budget projections into canonical.
 *
 * <p>Thin on purpose. The canonical shape is fixed by the projection records, the write rules are
 * fixed by the store, and the amounts are computed by whoever rebuilt them; there is nothing left
 * for this layer to decide.
 */
public final class BudgetProjectionService {

    private final BudgetProjectionStore store;

    public BudgetProjectionService(BudgetProjectionStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public BudgetProjectionResult project(BotBudgetProjection projection) {
        return store.project(Objects.requireNonNull(projection, "projection"));
    }

    public BudgetProjectionResult project(PartitionBudgetProjection projection) {
        return store.project(Objects.requireNonNull(projection, "projection"));
    }

    public BudgetRebuildResult rebuild(BudgetProjectionRebuild rebuild) {
        return store.rebuild(Objects.requireNonNull(rebuild, "rebuild"));
    }
}
