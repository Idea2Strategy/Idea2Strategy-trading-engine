package com.idea2strategy.trading.persistence.budget;

import com.idea2strategy.trading.application.budget.BudgetProjectionConflictException;
import com.idea2strategy.trading.application.budget.BudgetProjectionOutcome;
import com.idea2strategy.trading.application.budget.BudgetProjectionResult;
import com.idea2strategy.trading.application.budget.BudgetRebuildResult;
import com.idea2strategy.trading.application.port.BudgetProjectionStore;
import com.idea2strategy.trading.domain.budget.BotBudgetProjection;
import com.idea2strategy.trading.domain.budget.BudgetProjection;
import com.idea2strategy.trading.domain.budget.BudgetProjectionRebuild;
import com.idea2strategy.trading.domain.budget.PartitionBudgetProjection;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes the canonical budget projection tables.
 *
 * <p>Unlike the rest of the canonical trading write path there is no private predecessor here: the
 * service never had a budget table, and {@code trading.bot_budget_projections} and
 * {@code trading.partition_budget_projections} are new storage rather than a migration of anything.
 *
 * <p>They are declared rebuildable read models, and this store treats them as exactly that. It
 * persists amounts it is given and never derives them: the ledger, the active reservations and the
 * position valuations that produce those figures are owned elsewhere, and reading them here would
 * quietly make this the second place the accounting is defined.
 *
 * <p>What it does own is the canonical shape and the ordering. Canonical gives each row a
 * {@code last_event_sequence} and a {@code projection_hash} and nothing else to reason with, so
 * those two carry the whole write rule:
 *
 * <ul>
 *   <li>a later event sequence advances the row;
 *   <li>the same event sequence with the same hash is a redelivery and writes nothing, which is
 *       what replaces a private receipt table;
 *   <li>the same event sequence with a different hash is a conflict, because a rebuild of the same
 *       history cannot legitimately produce two answers;
 *   <li>an earlier event sequence is refused, so a slow rebuild cannot walk the row backwards.
 * </ul>
 *
 * <p>Rows are taken with {@code for update} before they are compared, so two rebuilds racing on one
 * bot serialise on the row instead of interleaving a read with a write.
 */
@Repository
public class PostgresBudgetProjectionStore implements BudgetProjectionStore {

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public PostgresBudgetProjectionStore(JdbcClient jdbc, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transaction = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
    }

    @Override
    public BudgetProjectionResult project(BotBudgetProjection projection) {
        Objects.requireNonNull(projection, "projection");
        return transaction.execute(status -> projectBot(projection));
    }

    @Override
    public BudgetProjectionResult project(PartitionBudgetProjection projection) {
        Objects.requireNonNull(projection, "projection");
        return transaction.execute(status -> projectPartition(projection));
    }

    /**
     * One transaction for the whole rebuild. Canonical enforces no relationship between the bot row
     * and its partition rows, so nothing would stop a half-applied rebuild from being stored and
     * read as a coherent answer; keeping the rows in one transaction is the only thing that does.
     */
    @Override
    public BudgetRebuildResult rebuild(BudgetProjectionRebuild rebuild) {
        Objects.requireNonNull(rebuild, "rebuild");
        return transaction.execute(status -> {
            BudgetProjectionResult bot = projectBot(rebuild.bot());
            List<BudgetProjectionResult> partitions = new ArrayList<>(rebuild.partitions().size());
            for (PartitionBudgetProjection partition : rebuild.partitions()) {
                partitions.add(projectPartition(partition));
            }
            return new BudgetRebuildResult(bot, partitions);
        });
    }

    private BudgetProjectionResult projectBot(BotBudgetProjection projection) {
        requireBotExists(projection.botId());
        Optional<StoredProjection> stored = lock("""
                select last_event_sequence, projection_hash
                from trading.bot_budget_projections
                where bot_id = :id
                for update
                """, projection.botId());
        Optional<BudgetProjectionResult> settled = settle(projection, stored);
        if (settled.isPresent()) {
            return settled.orElseThrow();
        }

        String hash = projection.projectionHash();
        if (stored.isEmpty()) {
            int inserted = jdbc.sql("""
                            insert into trading.bot_budget_projections (
                                bot_id, currency_code, available_cash_amount,
                                active_reservation_amount, invested_amount,
                                segregated_short_proceeds_amount, short_collateral_amount,
                                valuation_at, valuation_status, last_event_sequence,
                                projection_hash, updated_at
                            ) values (
                                :botId, :currencyCode, :availableCashAmount,
                                :activeReservationAmount, :investedAmount,
                                :segregatedShortProceedsAmount, :shortCollateralAmount,
                                :valuationAt, :valuationStatus, :lastEventSequence,
                                :projectionHash, current_timestamp
                            )
                            on conflict do nothing
                            """)
                    .param("botId", projection.botId())
                    .param("currencyCode", projection.currencyCode())
                    .param("availableCashAmount", projection.availableCashAmount())
                    .param("activeReservationAmount", projection.activeReservationAmount())
                    .param("investedAmount", projection.investedAmount())
                    .param("segregatedShortProceedsAmount",
                            projection.segregatedShortProceedsAmount())
                    .param("shortCollateralAmount", projection.shortCollateralAmount())
                    .param("valuationAt", offset(projection.valuationAt()))
                    .param("valuationStatus", projection.valuationStatus())
                    .param("lastEventSequence", projection.lastEventSequence())
                    .param("projectionHash", hash)
                    .update();
            return created(projection, hash, inserted);
        }

        int updated = jdbc.sql("""
                        update trading.bot_budget_projections
                        set currency_code = :currencyCode,
                            available_cash_amount = :availableCashAmount,
                            active_reservation_amount = :activeReservationAmount,
                            invested_amount = :investedAmount,
                            segregated_short_proceeds_amount = :segregatedShortProceedsAmount,
                            short_collateral_amount = :shortCollateralAmount,
                            valuation_at = :valuationAt,
                            valuation_status = :valuationStatus,
                            last_event_sequence = :lastEventSequence,
                            projection_hash = :projectionHash,
                            updated_at = current_timestamp
                        where bot_id = :botId
                          and last_event_sequence = :expectedSequence
                        """)
                .param("currencyCode", projection.currencyCode())
                .param("availableCashAmount", projection.availableCashAmount())
                .param("activeReservationAmount", projection.activeReservationAmount())
                .param("investedAmount", projection.investedAmount())
                .param("segregatedShortProceedsAmount", projection.segregatedShortProceedsAmount())
                .param("shortCollateralAmount", projection.shortCollateralAmount())
                .param("valuationAt", offset(projection.valuationAt()))
                .param("valuationStatus", projection.valuationStatus())
                .param("lastEventSequence", projection.lastEventSequence())
                .param("projectionHash", hash)
                .param("botId", projection.botId())
                .param("expectedSequence", stored.orElseThrow().lastEventSequence())
                .update();
        return advanced(projection, hash, updated);
    }

    private BudgetProjectionResult projectPartition(PartitionBudgetProjection projection) {
        requirePartitionBelongsToBot(projection.partitionId(), projection.botId());
        Optional<StoredProjection> stored = lock("""
                select last_event_sequence, projection_hash
                from trading.partition_budget_projections
                where partition_id = :id
                for update
                """, projection.partitionId());
        Optional<BudgetProjectionResult> settled = settle(projection, stored);
        if (settled.isPresent()) {
            return settled.orElseThrow();
        }

        String hash = projection.projectionHash();
        if (stored.isEmpty()) {
            int inserted = jdbc.sql("""
                            insert into trading.partition_budget_projections (
                                partition_id, bot_id, currency_code, budget_cap_amount,
                                active_reservation_amount, invested_amount,
                                segregated_short_proceeds_amount, short_collateral_amount,
                                valuation_at, valuation_status, last_event_sequence,
                                projection_hash, updated_at
                            ) values (
                                :partitionId, :botId, :currencyCode, :budgetCapAmount,
                                :activeReservationAmount, :investedAmount,
                                :segregatedShortProceedsAmount, :shortCollateralAmount,
                                :valuationAt, :valuationStatus, :lastEventSequence,
                                :projectionHash, current_timestamp
                            )
                            on conflict do nothing
                            """)
                    .param("partitionId", projection.partitionId())
                    .param("botId", projection.botId())
                    .param("currencyCode", projection.currencyCode())
                    .param("budgetCapAmount", projection.budgetCapAmount())
                    .param("activeReservationAmount", projection.activeReservationAmount())
                    .param("investedAmount", projection.investedAmount())
                    .param("segregatedShortProceedsAmount",
                            projection.segregatedShortProceedsAmount())
                    .param("shortCollateralAmount", projection.shortCollateralAmount())
                    .param("valuationAt", offset(projection.valuationAt()))
                    .param("valuationStatus", projection.valuationStatus())
                    .param("lastEventSequence", projection.lastEventSequence())
                    .param("projectionHash", hash)
                    .update();
            return created(projection, hash, inserted);
        }

        int updated = jdbc.sql("""
                        update trading.partition_budget_projections
                        set bot_id = :botId,
                            currency_code = :currencyCode,
                            budget_cap_amount = :budgetCapAmount,
                            active_reservation_amount = :activeReservationAmount,
                            invested_amount = :investedAmount,
                            segregated_short_proceeds_amount = :segregatedShortProceedsAmount,
                            short_collateral_amount = :shortCollateralAmount,
                            valuation_at = :valuationAt,
                            valuation_status = :valuationStatus,
                            last_event_sequence = :lastEventSequence,
                            projection_hash = :projectionHash,
                            updated_at = current_timestamp
                        where partition_id = :partitionId
                          and last_event_sequence = :expectedSequence
                        """)
                .param("botId", projection.botId())
                .param("currencyCode", projection.currencyCode())
                .param("budgetCapAmount", projection.budgetCapAmount())
                .param("activeReservationAmount", projection.activeReservationAmount())
                .param("investedAmount", projection.investedAmount())
                .param("segregatedShortProceedsAmount", projection.segregatedShortProceedsAmount())
                .param("shortCollateralAmount", projection.shortCollateralAmount())
                .param("valuationAt", offset(projection.valuationAt()))
                .param("valuationStatus", projection.valuationStatus())
                .param("lastEventSequence", projection.lastEventSequence())
                .param("projectionHash", hash)
                .param("partitionId", projection.partitionId())
                .param("expectedSequence", stored.orElseThrow().lastEventSequence())
                .update();
        return advanced(projection, hash, updated);
    }

    /**
     * Decides the cases that need no write: a refused stale rebuild, a recognised redelivery, or a
     * same-sequence answer that disagrees with the stored one.
     *
     * @return the result when the row is already settled, empty when it still has to be written
     */
    private static Optional<BudgetProjectionResult> settle(
            BudgetProjection projection, Optional<StoredProjection> stored) {
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        StoredProjection current = stored.orElseThrow();
        if (current.lastEventSequence() > projection.lastEventSequence()) {
            throw new BudgetProjectionConflictException(
                    "the stored projection is already at event sequence "
                            + current.lastEventSequence() + ", so the rebuild at "
                            + projection.lastEventSequence() + " is stale");
        }
        if (current.lastEventSequence() < projection.lastEventSequence()) {
            return Optional.empty();
        }
        if (!current.projectionHash().equals(projection.projectionHash())) {
            throw new BudgetProjectionConflictException(
                    "a different projection is already stored at event sequence "
                            + projection.lastEventSequence());
        }
        return Optional.of(new BudgetProjectionResult(
                projection.projectionId(), projection.lastEventSequence(),
                current.projectionHash(), BudgetProjectionOutcome.UNCHANGED));
    }

    private static BudgetProjectionResult created(
            BudgetProjection projection, String hash, int inserted) {
        if (inserted != 1) {
            throw new BudgetProjectionConflictException(
                    "the projection was created concurrently");
        }
        return new BudgetProjectionResult(
                projection.projectionId(), projection.lastEventSequence(), hash,
                BudgetProjectionOutcome.CREATED);
    }

    private static BudgetProjectionResult advanced(
            BudgetProjection projection, String hash, int updated) {
        if (updated != 1) {
            throw new BudgetProjectionConflictException("the projection changed concurrently");
        }
        return new BudgetProjectionResult(
                projection.projectionId(), projection.lastEventSequence(), hash,
                BudgetProjectionOutcome.ADVANCED);
    }

    private Optional<StoredProjection> lock(String sql, UUID id) {
        return jdbc.sql(sql)
                .param("id", id)
                .query((resultSet, rowNumber) -> new StoredProjection(
                        resultSet.getLong("last_event_sequence"),
                        resultSet.getString("projection_hash")))
                .optional();
    }

    /**
     * Canonical's foreign key would refuse an unknown bot too, but only as an integrity violation at
     * the end of the statement. Reading it here names the thing that was actually wrong.
     */
    private void requireBotExists(UUID botId) {
        Integer found = jdbc.sql("select 1 from bot.bots where id = :id")
                .param("id", botId)
                .query(Integer.class)
                .optional()
                .orElse(null);
        if (found == null) {
            throw new BudgetProjectionConflictException("no such bot owns a budget projection");
        }
    }

    /**
     * Canonical points the partition row at {@code bot.bot_partitions (bot_id, id)}, so a partition
     * budget can never be attributed to a bot that does not own the partition. Checking it here
     * turns that into a readable refusal instead of a foreign-key error.
     */
    private void requirePartitionBelongsToBot(UUID partitionId, UUID botId) {
        Integer found = jdbc.sql("""
                        select 1 from bot.bot_partitions where id = :id and bot_id = :botId
                        """)
                .param("id", partitionId)
                .param("botId", botId)
                .query(Integer.class)
                .optional()
                .orElse(null);
        if (found == null) {
            throw new BudgetProjectionConflictException(
                    "the partition is not owned by the bot the projection names");
        }
    }

    private static OffsetDateTime offset(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private record StoredProjection(long lastEventSequence, String projectionHash) {
    }
}
