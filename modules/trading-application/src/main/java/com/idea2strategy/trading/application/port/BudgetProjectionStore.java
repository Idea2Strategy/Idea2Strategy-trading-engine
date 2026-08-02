package com.idea2strategy.trading.application.port;

import com.idea2strategy.trading.application.budget.BudgetProjectionResult;
import com.idea2strategy.trading.application.budget.BudgetRebuildResult;
import com.idea2strategy.trading.domain.budget.BotBudgetProjection;
import com.idea2strategy.trading.domain.budget.BudgetProjectionRebuild;
import com.idea2strategy.trading.domain.budget.PartitionBudgetProjection;

/**
 * The canonical budget projection write path.
 *
 * <p>Every method takes an already-computed projection and stores it. None of them reads the
 * ledger, the reservations or the positions the amounts came from: these are rebuildable read
 * models, and the rebuild belongs to whoever owns those rules.
 */
public interface BudgetProjectionStore {

    /** Stores a bot's budget row, or reports the one canonical already holds for this event. */
    BudgetProjectionResult project(BotBudgetProjection projection);

    /** Stores a partition's budget row, or reports the one canonical already holds. */
    BudgetProjectionResult project(PartitionBudgetProjection projection);

    /** Stores a bot's row and its partitions' rows together, or none of them. */
    BudgetRebuildResult rebuild(BudgetProjectionRebuild rebuild);
}
