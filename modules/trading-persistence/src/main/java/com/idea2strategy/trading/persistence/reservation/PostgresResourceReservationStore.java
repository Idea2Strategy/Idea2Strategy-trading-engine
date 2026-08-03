package com.idea2strategy.trading.persistence.reservation;

import com.idea2strategy.trading.application.port.ResourceReservationStore;
import com.idea2strategy.trading.application.reservation.ConsumeReservationCommand;
import com.idea2strategy.trading.application.reservation.ReleaseReservationCommand;
import com.idea2strategy.trading.application.reservation.ReservationCommand;
import com.idea2strategy.trading.application.reservation.ReservationConflictException;
import com.idea2strategy.trading.application.reservation.ReservationVersionConflictException;
import com.idea2strategy.trading.application.reservation.SettleReservationCommand;
import com.idea2strategy.trading.domain.reservation.LotReservationAllocation;
import com.idea2strategy.trading.domain.reservation.ReservationComponentLink;
import com.idea2strategy.trading.domain.reservation.ReservationEventType;
import com.idea2strategy.trading.domain.reservation.ReservationOpening;
import com.idea2strategy.trading.domain.reservation.ReservationReleaseCause;
import com.idea2strategy.trading.domain.reservation.ReservationResourceType;
import com.idea2strategy.trading.domain.reservation.ReservationStatus;
import com.idea2strategy.trading.domain.reservation.ReservationTransition;
import com.idea2strategy.trading.domain.reservation.ResourceReservation;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes the canonical reservation tables.
 *
 * <p>The private schema kept a reservation, a command receipt, a movement log and a lot breakdown in
 * four {@code execution_resource_reservation*} tables keyed on an order. Canonical keeps the same
 * facts, but re-anchored and with provenance attached:
 *
 * <ul>
 *   <li>{@code trading.resource_reservations} belongs to an <strong>intent</strong>. Buying power is
 *       committed when the intent is approved, which is before the order exists, so the private
 *       {@code order_id} key could not have been right.
 *   <li>{@code trading.order_component_reservations} is where the order finally appears, once the
 *       intent has been composed into one. It is also the only path
 *       {@code assert_fill_reservation_consumption} can take from a fill event to the allocation
 *       that justifies it.
 *   <li>{@code trading.reservation_events} replaces the movement log, and its
 *       {@code (reservation_id, event_key)} unique index replaces the command-receipt table. The
 *       private request fingerprint lives on as {@code event_hash}.
 *   <li>{@code trading.position_lot_reservations} replaces the lot breakdown, holding only the
 *       locked quantity per lot.
 * </ul>
 *
 * <p>Two deferred constraint triggers decide whether any of this commits.
 * {@code assert_reservation_event_totals} rebuilds the projection from the events — contiguous
 * sequence, matching totals, matching latest status — and {@code assert_fill_reservation_consumption}
 * makes a fill event prove itself against the fill's component allocation. Both are checked here
 * first, so a bad write fails at the boundary that caused it with a readable message instead of at
 * commit.
 */
@Repository
public class PostgresResourceReservationStore implements ResourceReservationStore {

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public PostgresResourceReservationStore(JdbcClient jdbc, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transaction = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
    }

    @Override
    public ResourceReservation createOrLoad(ReservationOpening opening) {
        Objects.requireNonNull(opening, "opening");
        return transaction.execute(status -> createInTransaction(opening));
    }

    @Override
    public ResourceReservation attachToOrderComponent(ReservationComponentLink link) {
        Objects.requireNonNull(link, "link");
        return transaction.execute(status -> attachInTransaction(link));
    }

    @Override
    public ResourceReservation apply(ReservationCommand command) {
        Objects.requireNonNull(command, "command");
        return transaction.execute(status -> applyInTransaction(command));
    }

    private ResourceReservation createInTransaction(ReservationOpening opening) {
        ResourceReservation desired = opening.reservation();
        requireIntentApproves(opening);
        String requestHash = CanonicalReservationIdentity.createdHash(opening);

        if (insertReservation(opening) != 1) {
            return replayCreate(desired.reservationId(), requestHash);
        }
        lockLots(opening);
        appendEvent(
                opening.scope().botId(), opening.scope().partitionId(), desired, opening.created(),
                opening.createdEventId(), null, requestHash);
        return desired;
    }

    /**
     * Canonical already makes {@code (intent_id, reservation_key)} unique and the reservation id is
     * derived from exactly that pair, so this is a redelivery rather than a second reservation. It
     * only reports as one if the stored {@code CREATED} event was written from the same request.
     */
    private ResourceReservation replayCreate(UUID reservationId, String requestHash) {
        ResourceReservation stored = find(reservationId, false)
                .orElseThrow(() -> conflict("reservation identity conflict"));
        String storedHash = jdbc.sql("""
                        select event_hash from trading.reservation_events
                        where reservation_id = :reservationId and event_key = 'CREATED'
                        """)
                .param("reservationId", reservationId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> conflict("the stored reservation has no creation event"));
        if (!storedHash.equals(requestHash)) {
            throw conflict("a reservation already exists for this intent and resource");
        }
        return stored;
    }

    private ResourceReservation attachInTransaction(ReservationComponentLink link) {
        ResourceReservation reservation = find(link.reservationId(), false)
                .orElseThrow(() -> conflict("no reservation to attach"));
        UUID componentIntent = jdbc.sql("""
                        select intent_id from trading.order_components
                        where id = :componentId and bot_id = :botId and partition_id = :partitionId
                        """)
                .param("componentId", link.orderComponentId())
                .param("botId", link.scope().botId())
                .param("partitionId", link.scope().partitionId())
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> conflict("no order component in this partition to attach to"));
        // Canonical cannot see this: order_component_reservations has no key reaching the intent, so
        // a reservation could otherwise be attached to a component composed from a different one.
        if (!componentIntent.equals(reservation.intentId())) {
            throw conflict("the order component was composed from a different intent");
        }

        boolean amount = reservation.measuredInAmount();
        int inserted = jdbc.sql("""
                        insert into trading.order_component_reservations (
                            bot_id, partition_id, reservation_id, order_component_id,
                            reserved_amount, reserved_quantity
                        ) values (
                            :botId, :partitionId, :reservationId, :orderComponentId,
                            :reservedAmount, :reservedQuantity
                        )
                        on conflict do nothing
                        """)
                .param("botId", link.scope().botId())
                .param("partitionId", link.scope().partitionId())
                .param("reservationId", link.reservationId())
                .param("orderComponentId", link.orderComponentId())
                .param("reservedAmount", amount ? reservation.reserved() : null)
                .param("reservedQuantity", amount ? null : reservation.reserved())
                .update();
        if (inserted != 1) {
            UUID attached = jdbc.sql("""
                            select order_component_id from trading.order_component_reservations
                            where reservation_id = :reservationId
                            """)
                    .param("reservationId", link.reservationId())
                    .query(UUID.class)
                    .single();
            if (!attached.equals(link.orderComponentId())) {
                throw conflict("the reservation already backs another order component");
            }
        }
        return reservation;
    }

    private ResourceReservation applyInTransaction(ReservationCommand command) {
        ReservationEventType eventType = eventTypeOf(command);
        UUID sourceFillId = sourceFillOf(command);
        String eventKey = CanonicalReservationIdentity.eventKey(
                eventType, sourceFillId, command.botEventId());
        String requestHash = CanonicalReservationIdentity.commandHash(
                eventType, command.reservationId(), command.botEventId(), sourceFillId,
                measureOf(command), command.occurredAt());

        Optional<String> replayed = storedEventHash(command.reservationId(), eventKey);
        if (replayed.isPresent()) {
            return replay(command.reservationId(), eventKey, requestHash, replayed.orElseThrow());
        }

        ResourceReservation current = find(command.reservationId(), true)
                .orElseThrow(() -> conflict("no reservation to change"));
        // Re-read under the row lock: a concurrent writer may have appended between the two.
        replayed = storedEventHash(command.reservationId(), eventKey);
        if (replayed.isPresent()) {
            return replay(command.reservationId(), eventKey, requestHash, replayed.orElseThrow());
        }
        if (current.lastEventSequence() != command.expectedSequence()) {
            throw new ReservationVersionConflictException(
                    "expected reservation sequence %d but found %d"
                            .formatted(command.expectedSequence(), current.lastEventSequence()));
        }

        ReservationTransition transition = transition(current, command);
        if (eventType.requiresSourceFill()) {
            requireFillAllocationJustifies(current, sourceFillId, transition.consumedDelta());
        }

        Scope scope = scopeOf(current.reservationId());
        appendEvent(
                scope.botId(), scope.partitionId(), transition.reservation(), transition,
                command.botEventId(), sourceFillId, requestHash);
        ResourceReservation next = transition.reservation();
        int updated = jdbc.sql("""
                        update trading.resource_reservations
                        set consumed_amount = :consumedAmount,
                            released_amount = :releasedAmount,
                            consumed_quantity = :consumedQuantity,
                            released_quantity = :releasedQuantity,
                            status = cast(:status as trading.reservation_status),
                            last_event_sequence = :lastEventSequence
                        where id = :reservationId and last_event_sequence = :expectedSequence
                        """)
                .param("consumedAmount", next.measuredInAmount() ? next.consumed() : zero())
                .param("releasedAmount", next.measuredInAmount() ? next.released() : zero())
                .param("consumedQuantity", next.measuredInAmount() ? zero() : next.consumed())
                .param("releasedQuantity", next.measuredInAmount() ? zero() : next.released())
                .param("status", next.status().name())
                .param("lastEventSequence", next.lastEventSequence())
                .param("reservationId", next.reservationId())
                .param("expectedSequence", command.expectedSequence())
                .update();
        if (updated != 1) {
            throw new ReservationVersionConflictException("the reservation changed concurrently");
        }
        if (next.status() != ReservationStatus.ACTIVE) {
            unlockLots(next);
        }
        return next;
    }

    /**
     * The event is already there. It only reports as a redelivery if it was written from the same
     * request; otherwise two different changes are claiming the same canonical event key.
     */
    private ResourceReservation replay(
            UUID reservationId, String eventKey, String requestHash, String storedHash) {
        if (!storedHash.equals(requestHash)) {
            throw conflict("event " + eventKey + " was already recorded with different content");
        }
        return find(reservationId, false)
                .orElseThrow(() -> conflict("the recorded event has no reservation"));
    }

    private static ReservationTransition transition(
            ResourceReservation current, ReservationCommand command) {
        if (command instanceof ConsumeReservationCommand consume) {
            return current.consumeByFill(consume.consumed(), consume.occurredAt());
        }
        if (command instanceof SettleReservationCommand settle) {
            return current.settleByFill(settle.consumed(), settle.occurredAt());
        }
        ReleaseReservationCommand release = (ReleaseReservationCommand) command;
        return current.release(release.cause(), release.occurredAt());
    }

    private static ReservationEventType eventTypeOf(ReservationCommand command) {
        if (command instanceof ConsumeReservationCommand) {
            return ReservationEventType.CONSUMED_BY_FILL;
        }
        if (command instanceof SettleReservationCommand) {
            return ReservationEventType.SETTLED_BY_FILL;
        }
        return ((ReleaseReservationCommand) command).cause().eventType();
    }

    private static UUID sourceFillOf(ReservationCommand command) {
        if (command instanceof ConsumeReservationCommand consume) {
            return consume.sourceFillId();
        }
        if (command instanceof SettleReservationCommand settle) {
            return settle.sourceFillId();
        }
        return null;
    }

    private static BigDecimal measureOf(ReservationCommand command) {
        if (command instanceof ConsumeReservationCommand consume) {
            return consume.consumed();
        }
        if (command instanceof SettleReservationCommand settle) {
            return settle.consumed();
        }
        return null;
    }

    /**
     * Mirrors {@code assert_fill_reservation_consumption}, which fails the transaction at commit.
     *
     * <p>The reservation has to have been attached to an order component, that component has to have
     * taken an allocation of this fill, and for cash the consumed amount has to be exactly the cash
     * the allocation settled. Reading it here turns a deferred database error into a readable one.
     */
    private void requireFillAllocationJustifies(
            ResourceReservation reservation, UUID sourceFillId, BigDecimal consumed) {
        Allocation allocation = jdbc.sql("""
                        select fill_allocation.allocated_quantity,
                               fill_allocation.allocated_settlement_cash_delta
                        from trading.order_component_reservations link
                        join trading.fill_component_allocations fill_allocation
                          on fill_allocation.order_component_id = link.order_component_id
                         and fill_allocation.fill_id = :fillId
                        where link.reservation_id = :reservationId
                        """)
                .param("fillId", sourceFillId)
                .param("reservationId", reservation.reservationId())
                .query((resultSet, rowNumber) -> new Allocation(
                        resultSet.getBigDecimal("allocated_quantity"),
                        resultSet.getBigDecimal("allocated_settlement_cash_delta")))
                .optional()
                .orElseThrow(() -> conflict(
                        "no allocation of this fill reaches the reservation's order component"));

        BigDecimal expected = switch (reservation.resourceType()) {
            case CASH_BUYING_POWER -> allocation.settlementCashDelta().abs();
            case POSITION_QUANTITY -> allocation.quantity();
            // Canonical asserts nothing for collateral, so neither does this.
            case SHORT_COLLATERAL_CASH -> consumed;
        };
        if (expected.compareTo(consumed) != 0) {
            throw conflict(
                    "the fill allocation settled " + expected.toPlainString() + " but "
                            + consumed.toPlainString() + " is being consumed");
        }
    }

    private void requireIntentApproves(ReservationOpening opening) {
        ResourceReservation reservation = opening.reservation();
        IntentFacts facts = jdbc.sql("""
                        select flow_id, instrument_id, cast(decision as varchar) as decision
                        from trading.order_intents
                        where id = :intentId and bot_id = :botId and partition_id = :partitionId
                        """)
                .param("intentId", reservation.intentId())
                .param("botId", opening.scope().botId())
                .param("partitionId", opening.scope().partitionId())
                .query((resultSet, rowNumber) -> new IntentFacts(
                        resultSet.getObject("flow_id", UUID.class),
                        resultSet.getObject("instrument_id", UUID.class),
                        resultSet.getString("decision")))
                .optional()
                .orElseThrow(() -> conflict("no intent of this partition owns the reservation"));
        if (!facts.flowId().equals(opening.flowId())) {
            throw conflict("the reservation names a different flow than its intent");
        }
        // Canonical cannot see this: resource_reservations has no instrument key back to the intent.
        if (reservation.instrumentId() != null
                && !reservation.instrumentId().equals(facts.instrumentId())) {
            throw conflict("the reservation names a different instrument than its intent");
        }
        // IntentDecision.executes(): APPROVED and REDUCED both carry a final quantity that becomes
        // an order, and an order needs its reservation. Only the non-executing three are refused.
        if (!com.idea2strategy.trading.domain.intent.IntentDecision.valueOf(facts.decision()).executes()) {
            throw conflict("only an executing intent may reserve a resource");
        }
    }

    private int insertReservation(ReservationOpening opening) {
        ResourceReservation reservation = opening.reservation();
        boolean amount = reservation.measuredInAmount();
        return jdbc.sql("""
                        insert into trading.resource_reservations (
                            id, reservation_key, bot_id, partition_id, flow_id, intent_id,
                            resource_type, currency_code, instrument_id, buffer_policy_id,
                            fee_policy_id, short_risk_policy_id, precision_rules_version, status,
                            reference_price, reference_observed_at, reference_market_hash,
                            base_notional, fixed_slippage_amount, estimated_fee_amount,
                            buffer_amount, reserved_amount, consumed_amount, released_amount,
                            reserved_quantity, consumed_quantity, released_quantity,
                            created_event_id, created_at, last_event_sequence
                        ) values (
                            :id, :reservationKey, :botId, :partitionId, :flowId, :intentId,
                            cast(:resourceType as trading.reservation_resource_type), :currencyCode,
                            :instrumentId, :bufferPolicyId, :feePolicyId, :shortRiskPolicyId,
                            :precisionRulesVersion,
                            cast(:status as trading.reservation_status),
                            :referencePrice, :referenceObservedAt, :referenceMarketHash,
                            :baseNotional, :fixedSlippageAmount, :estimatedFeeAmount,
                            :bufferAmount, :reservedAmount, 0, 0, :reservedQuantity, 0, 0,
                            :createdEventId, :createdAt, :lastEventSequence
                        )
                        on conflict do nothing
                        """)
                .param("id", reservation.reservationId())
                .param("reservationKey", reservation.reservationKey())
                .param("botId", opening.scope().botId())
                .param("partitionId", opening.scope().partitionId())
                .param("flowId", opening.flowId())
                .param("intentId", reservation.intentId())
                .param("resourceType", reservation.resourceType().name())
                .param("currencyCode", reservation.currencyCode())
                .param("instrumentId", reservation.instrumentId())
                .param("bufferPolicyId", opening.pins().bufferPolicyId())
                .param("feePolicyId", opening.pins().feePolicyId())
                .param("shortRiskPolicyId", opening.pins().shortRiskPolicyId())
                .param("precisionRulesVersion", opening.pins().precisionRulesVersion())
                .param("status", reservation.status().name())
                .param("referencePrice", opening.pricing().referencePrice())
                .param("referenceObservedAt", offset(opening.pricing().referenceObservedAt()))
                .param("referenceMarketHash", opening.pricing().referenceMarketHash())
                .param("baseNotional", opening.pricing().baseNotional())
                .param("fixedSlippageAmount", opening.pricing().fixedSlippageAmount())
                .param("estimatedFeeAmount", opening.pricing().estimatedFeeAmount())
                .param("bufferAmount", opening.pricing().bufferAmount())
                .param("reservedAmount", amount ? reservation.reserved() : null)
                .param("reservedQuantity", amount ? null : reservation.reserved())
                .param("createdEventId", opening.createdEventId())
                .param("createdAt", offset(reservation.createdAt()))
                .param("lastEventSequence", reservation.lastEventSequence())
                .update();
    }

    private void appendEvent(
            UUID botId,
            UUID partitionId,
            ResourceReservation reservation,
            ReservationTransition transition,
            UUID botEventId,
            UUID sourceFillId,
            String eventHash) {
        boolean amount = reservation.measuredInAmount();
        int inserted = jdbc.sql("""
                        insert into trading.reservation_events (
                            bot_id, partition_id, reservation_id, bot_event_id, source_fill_id,
                            event_key, reservation_sequence, event_type, consumed_amount_delta,
                            released_amount_delta, consumed_quantity_delta,
                            released_quantity_delta, status_after, occurred_at, event_hash
                        ) values (
                            :botId, :partitionId, :reservationId, :botEventId, :sourceFillId,
                            :eventKey, :reservationSequence,
                            cast(:eventType as trading.reservation_event_type),
                            :consumedAmountDelta, :releasedAmountDelta, :consumedQuantityDelta,
                            :releasedQuantityDelta,
                            cast(:statusAfter as trading.reservation_status), :occurredAt,
                            :eventHash
                        )
                        on conflict do nothing
                        """)
                .param("botId", botId)
                .param("partitionId", partitionId)
                .param("reservationId", reservation.reservationId())
                .param("botEventId", botEventId)
                .param("sourceFillId", sourceFillId)
                .param("eventKey", CanonicalReservationIdentity.eventKey(
                        transition.eventType(), sourceFillId, botEventId))
                .param("reservationSequence", transition.sequence())
                .param("eventType", transition.eventType().name())
                .param("consumedAmountDelta", amount ? transition.consumedDelta() : null)
                .param("releasedAmountDelta", amount ? transition.releasedDelta() : null)
                .param("consumedQuantityDelta", amount ? null : transition.consumedDelta())
                .param("releasedQuantityDelta", amount ? null : transition.releasedDelta())
                .param("statusAfter", transition.statusAfter().name())
                .param("occurredAt", offset(transition.occurredAt()))
                .param("eventHash", eventHash)
                .update();
        if (inserted != 1) {
            throw conflict("the reservation already recorded this event");
        }
    }

    /**
     * Locks the lot remainders a quantity reservation draws on.
     *
     * <p>{@code position_lot_projections.active_reserved_quantity} is the column the position write
     * path deliberately leaves alone, and canonical
     * {@code lot_projection_reservation_within_remaining} is what turns it into a real lock: the
     * update fails if another flow has already reserved the remainder.
     */
    private void lockLots(ReservationOpening opening) {
        for (LotReservationAllocation allocation : opening.reservation().lotAllocations()) {
            int inserted = jdbc.sql("""
                            insert into trading.position_lot_reservations (
                                bot_id, partition_id, flow_id, reservation_id, position_lot_id,
                                reserved_quantity
                            ) values (
                                :botId, :partitionId, :flowId, :reservationId, :positionLotId,
                                :reservedQuantity
                            )
                            """)
                    .param("botId", opening.scope().botId())
                    .param("partitionId", opening.scope().partitionId())
                    .param("flowId", opening.flowId())
                    .param("reservationId", opening.reservation().reservationId())
                    .param("positionLotId", allocation.lotId())
                    .param("reservedQuantity", allocation.reservedQuantity())
                    .update();
            if (inserted != 1) {
                throw conflict("the lot could not be locked");
            }
            takeLotQuantity(allocation.lotId(), allocation.reservedQuantity());
        }
    }

    /**
     * Mirrors {@code lot_projection_reservation_within_remaining}, which would otherwise surface as
     * an opaque constraint violation. The projection row is locked first, so two flows racing for
     * the same remainder are serialised rather than both seeing it free.
     */
    private void takeLotQuantity(UUID positionLotId, BigDecimal quantity) {
        Lock lock = jdbc.sql("""
                        select remaining_quantity, active_reserved_quantity
                        from trading.position_lot_projections
                        where position_lot_id = :positionLotId
                        for update
                        """)
                .param("positionLotId", positionLotId)
                .query((resultSet, rowNumber) -> new Lock(
                        resultSet.getBigDecimal("remaining_quantity"),
                        resultSet.getBigDecimal("active_reserved_quantity")))
                .optional()
                .orElseThrow(() -> conflict("no lot projection to reserve against"));
        if (lock.activeReservedQuantity().add(quantity).compareTo(lock.remainingQuantity()) > 0) {
            throw conflict("the lot remainder is already reserved");
        }
        moveActiveReservedQuantity(positionLotId, quantity);
    }

    /**
     * A terminal reservation holds nothing, whether it settled or was given back.
     *
     * <p>Canonical records no per-lot consumption, so the lock is held whole for the life of the
     * reservation and dropped whole at the end of it.
     */
    private void unlockLots(ResourceReservation reservation) {
        for (LotReservationAllocation allocation : reservation.lotAllocations()) {
            moveActiveReservedQuantity(allocation.lotId(), allocation.reservedQuantity().negate());
        }
    }

    private void moveActiveReservedQuantity(UUID positionLotId, BigDecimal delta) {
        int updated = jdbc.sql("""
                        update trading.position_lot_projections
                        set active_reserved_quantity = active_reserved_quantity + :delta
                        where position_lot_id = :positionLotId
                        """)
                .param("delta", delta)
                .param("positionLotId", positionLotId)
                .update();
        if (updated != 1) {
            throw conflict("no lot projection to reserve against");
        }
    }

    private Optional<String> storedEventHash(UUID reservationId, String eventKey) {
        return jdbc.sql("""
                        select event_hash from trading.reservation_events
                        where reservation_id = :reservationId and event_key = :eventKey
                        """)
                .param("reservationId", reservationId)
                .param("eventKey", eventKey)
                .query(String.class)
                .optional();
    }

    private Scope scopeOf(UUID reservationId) {
        return jdbc.sql("""
                        select bot_id, partition_id from trading.resource_reservations
                        where id = :reservationId
                        """)
                .param("reservationId", reservationId)
                .query((resultSet, rowNumber) -> new Scope(
                        resultSet.getObject("bot_id", UUID.class),
                        resultSet.getObject("partition_id", UUID.class)))
                .single();
    }

    private Optional<ResourceReservation> find(UUID reservationId, boolean forUpdate) {
        return jdbc.sql("""
                        select id, intent_id, cast(resource_type as varchar) as resource_type,
                               currency_code, instrument_id, reserved_amount, consumed_amount,
                               released_amount, reserved_quantity, consumed_quantity,
                               released_quantity, cast(status as varchar) as status, created_at,
                               last_event_sequence
                        from trading.resource_reservations
                        where id = :reservationId
                        """ + (forUpdate ? " for update" : ""))
                .param("reservationId", reservationId)
                .query((resultSet, rowNumber) -> toRow(resultSet))
                .optional()
                .map(this::toReservation);
    }

    private static Row toRow(ResultSet resultSet) throws SQLException {
        ReservationResourceType resourceType =
                ReservationResourceType.valueOf(resultSet.getString("resource_type"));
        boolean amount = resourceType.measuredInAmount();
        return new Row(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("intent_id", UUID.class),
                resourceType,
                resultSet.getString("currency_code"),
                resultSet.getObject("instrument_id", UUID.class),
                resultSet.getBigDecimal(amount ? "reserved_amount" : "reserved_quantity"),
                resultSet.getBigDecimal(amount ? "consumed_amount" : "consumed_quantity"),
                resultSet.getBigDecimal(amount ? "released_amount" : "released_quantity"),
                ReservationStatus.valueOf(resultSet.getString("status")),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                resultSet.getLong("last_event_sequence"));
    }

    private ResourceReservation toReservation(Row row) {
        Latest latest = latestEvent(row.reservationId());
        return new ResourceReservation(
                row.reservationId(), row.intentId(), row.resourceType(), row.currencyCode(),
                row.instrumentId(), row.reserved(), row.consumed(), row.released(), row.status(),
                row.status() == ReservationStatus.ACTIVE ? null : latest.cause(),
                row.lastEventSequence(), row.createdAt(), latest.occurredAt(),
                row.resourceType() == ReservationResourceType.POSITION_QUANTITY
                        ? lockedLots(row.reservationId())
                        : List.of());
    }

    /**
     * The private schema kept {@code updated_at} and {@code terminal_reason} on the reservation.
     * Canonical keeps neither: the last event's moment is when the reservation last moved, and its
     * type is the reason it stopped.
     */
    private Latest latestEvent(UUID reservationId) {
        return jdbc.sql("""
                        select cast(event_type as varchar) as event_type, occurred_at
                        from trading.reservation_events
                        where reservation_id = :reservationId
                        order by reservation_sequence desc
                        limit 1
                        """)
                .param("reservationId", reservationId)
                .query((resultSet, rowNumber) -> {
                    ReservationEventType eventType =
                            ReservationEventType.valueOf(resultSet.getString("event_type"));
                    ReservationReleaseCause cause =
                            eventType.name().startsWith("RELEASED_BY_")
                                    ? ReservationReleaseCause.of(eventType)
                                    : null;
                    return new Latest(
                            cause, resultSet.getObject("occurred_at", OffsetDateTime.class).toInstant());
                })
                .single();
    }

    private List<LotReservationAllocation> lockedLots(UUID reservationId) {
        return jdbc.sql("""
                        select lock_row.position_lot_id, lock_row.reserved_quantity, lot.opened_at
                        from trading.position_lot_reservations lock_row
                        join trading.position_lots lot on lot.id = lock_row.position_lot_id
                        where lock_row.reservation_id = :reservationId
                        order by lot.opened_at, lot.id
                        """)
                .param("reservationId", reservationId)
                .query((resultSet, rowNumber) -> new LotReservationAllocation(
                        resultSet.getObject("position_lot_id", UUID.class),
                        resultSet.getObject("opened_at", OffsetDateTime.class).toInstant(),
                        resultSet.getBigDecimal("reserved_quantity")))
                .list();
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(8);
    }

    private static OffsetDateTime offset(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static ReservationConflictException conflict(String message) {
        return new ReservationConflictException(message);
    }

    private record Allocation(BigDecimal quantity, BigDecimal settlementCashDelta) {}

    private record IntentFacts(UUID flowId, UUID instrumentId, String decision) {}

    private record Scope(UUID botId, UUID partitionId) {}

    private record Latest(ReservationReleaseCause cause, Instant occurredAt) {}

    private record Lock(BigDecimal remainingQuantity, BigDecimal activeReservedQuantity) {}

    private record Row(
            UUID reservationId,
            UUID intentId,
            ReservationResourceType resourceType,
            String currencyCode,
            UUID instrumentId,
            BigDecimal reserved,
            BigDecimal consumed,
            BigDecimal released,
            ReservationStatus status,
            Instant createdAt,
            long lastEventSequence) {}
}
