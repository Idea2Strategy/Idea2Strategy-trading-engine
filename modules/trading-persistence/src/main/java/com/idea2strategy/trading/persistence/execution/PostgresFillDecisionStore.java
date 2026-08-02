package com.idea2strategy.trading.persistence.execution;

import com.idea2strategy.trading.application.port.FillDecisionStore;
import com.idea2strategy.trading.application.execution.FillDecisionConflictException;
import com.idea2strategy.trading.domain.execution.FillDecision;
import com.idea2strategy.trading.domain.execution.VirtualFill;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class PostgresFillDecisionStore implements FillDecisionStore {
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public PostgresFillDecisionStore(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    @Override
    public FillDecision createOrLoad(FillDecision decision) {
        Objects.requireNonNull(decision, "decision");
        return transaction.execute(status -> createOrLoadInTransaction(decision));
    }

    private FillDecision createOrLoadInTransaction(FillDecision decision) {
        VirtualFill fill = decision.value();
        int inserted = jdbc.sql("""
                insert into trading.virtual_fill_decision (
                    decision_id, request_fingerprint, order_id, order_version, snapshot_id,
                    instrument_id, observed_at, bid_price, bid_size, ask_price, ask_size,
                    last_trade_price, last_trade_size, trailing_reference_price, eligibility,
                    fill_id, fill_quantity, reference_price, fill_price, notional,
                    slippage_amount, fee, partial, evaluated_at
                ) values (
                    :decisionId, :fingerprint, :orderId, :orderVersion, :snapshotId,
                    :instrumentId, :observedAt, :bidPrice, :bidSize, :askPrice, :askSize,
                    :lastTradePrice, :lastTradeSize, :trailingReferencePrice, :eligibility,
                    :fillId, :fillQuantity, :referencePrice, :fillPrice, :notional,
                    :slippageAmount, :fee, :partial, :evaluatedAt
                ) on conflict do nothing
                """)
                .param("decisionId", decision.decisionId())
                .param("fingerprint", decision.requestFingerprint())
                .param("orderId", decision.orderId())
                .param("orderVersion", decision.orderVersion())
                .param("snapshotId", decision.snapshotId())
                .param("instrumentId", decision.snapshot().instrumentId())
                .param("observedAt", offset(decision.snapshot().observedAt()))
                .param("bidPrice", decision.snapshot().bidPrice())
                .param("bidSize", decision.snapshot().bidSize())
                .param("askPrice", decision.snapshot().askPrice())
                .param("askSize", decision.snapshot().askSize())
                .param("lastTradePrice", decision.snapshot().lastTradePrice())
                .param("lastTradeSize", decision.snapshot().lastTradeSize())
                .param("trailingReferencePrice", decision.snapshot().trailingReferencePrice())
                .param("eligibility", decision.eligibility().name())
                .param("fillId", fill == null ? null : fill.fillId())
                .param("fillQuantity", fill == null ? null : fill.quantity())
                .param("referencePrice", fill == null ? null : fill.referencePrice())
                .param("fillPrice", fill == null ? null : fill.price())
                .param("notional", fill == null ? null : fill.notional())
                .param("slippageAmount", fill == null ? null : fill.slippageAmount())
                .param("fee", fill == null ? null : fill.fee())
                .param("partial", fill == null ? null : fill.partial())
                .param("evaluatedAt", offset(decision.evaluatedAt()))
                .update();
        FillDecision stored = new JooqFillDecisionQuery(jdbc).findById(decision.decisionId())
                .orElseThrow(FillDecisionConflictException::new);
        if (inserted == 0 && (!stored.requestFingerprint().equals(decision.requestFingerprint())
                || !stored.equals(decision))) {
            throw new FillDecisionConflictException();
        }
        return stored;
    }

    private static OffsetDateTime offset(java.time.Instant instant) { return instant.atOffset(ZoneOffset.UTC); }
}
