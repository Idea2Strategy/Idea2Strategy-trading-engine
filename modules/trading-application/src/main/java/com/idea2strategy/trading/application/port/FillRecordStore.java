package com.idea2strategy.trading.application.port;

import com.idea2strategy.trading.domain.fill.FillPosting;
import com.idea2strategy.trading.domain.fill.FillRecord;

public interface FillRecordStore {

    /**
     * Records one official fill, or returns the one already recorded for the same work.
     *
     * <p>Takes a posting rather than the record alone because the canonical fill row cannot be
     * written without its partition, the quote it was priced from, the platform rules it was charged
     * under, the official event that caused it and how it splits across the order components.
     *
     * <p>The caller must advance the order in the same transaction. Canonical
     * {@code assert_order_fill_state} compares the order projection against the sum of its fills at
     * commit, so a fill recorded on its own fails the transaction.
     */
    FillRecord appendOrLoad(FillPosting posting);
}
