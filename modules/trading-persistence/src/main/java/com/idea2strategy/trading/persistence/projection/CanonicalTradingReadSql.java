package com.idea2strategy.trading.persistence.projection;

import java.util.List;
import java.util.Map;

/**
 * Canonical read contract for the trading query projections.
 *
 * <p>Every statement reads only schema-qualified canonical tables owned by the central Flyway
 * bundle. The trading runtime never owns this DDL, so these queries are the complete machine
 * readable description of what F15 reads. {@code db/migration-contributions/fixtures/
 * trading_read_projection_contract.sql.fixture} prepares the exact same statements against a
 * migrated canonical database, which keeps the shipped SQL and its executable proof in step.
 *
 * <p>Bind parameters are positional and always ordered {@code owner_account_id, bot_id
 * [, partition_id [, flow_id]] [, limit, offset]}.
 */
public final class CanonicalTradingReadSql {

    private static final String OWNED_BOT_SCOPE = """
            with owned_bot as (
                select b.id as bot_id
                  from bot.bots b
                 where b.owner_account_id = cast(? as uuid)
                   and b.id = cast(? as uuid)
                   and b.deleted_at is null
            )""";

    private static final String OWNED_PARTITION_SCOPE = """
            with owned_bot as (
                select b.id as bot_id
                  from bot.bots b
                 where b.owner_account_id = cast(? as uuid)
                   and b.id = cast(? as uuid)
                   and b.deleted_at is null
            ),
            scope as (
                select ob.bot_id, p.id as partition_id
                  from owned_bot ob
                  join bot.bot_partitions p
                    on p.bot_id = ob.bot_id
                   and p.id = cast(? as uuid)
            )""";

    private static final String OWNED_FLOW_SCOPE = """
            with owned_bot as (
                select b.id as bot_id
                  from bot.bots b
                 where b.owner_account_id = cast(? as uuid)
                   and b.id = cast(? as uuid)
                   and b.deleted_at is null
            ),
            scope as (
                select ob.bot_id, p.id as partition_id, f.id as flow_id
                  from owned_bot ob
                  join bot.bot_partitions p
                    on p.bot_id = ob.bot_id
                   and p.id = cast(? as uuid)
                  join bot.flows f
                    on f.partition_id = p.id
                   and f.id = cast(? as uuid)
            )""";

    /** Bot level budget. Parameters: owner_account_id, bot_id. */
    public static final String BOT_BUDGET = OWNED_BOT_SCOPE + """

            select p.bot_id,
                   p.currency_code,
                   p.available_cash_amount,
                   p.active_reservation_amount,
                   p.invested_amount,
                   p.segregated_short_proceeds_amount,
                   p.short_collateral_amount,
                   p.valuation_at,
                   p.valuation_status,
                   p.last_event_sequence,
                   p.updated_at
              from trading.bot_budget_projections p
              join owned_bot ob on ob.bot_id = p.bot_id
            """;

    /** Partition budgets under one bot. Parameters: owner_account_id, bot_id, limit, offset. */
    public static final String PARTITION_BUDGETS = OWNED_BOT_SCOPE + """

            select p.bot_id,
                   p.partition_id,
                   p.currency_code,
                   p.budget_cap_amount,
                   p.active_reservation_amount,
                   p.invested_amount,
                   p.segregated_short_proceeds_amount,
                   p.short_collateral_amount,
                   p.valuation_at,
                   p.valuation_status,
                   p.last_event_sequence,
                   p.updated_at
              from trading.partition_budget_projections p
              join owned_bot ob on ob.bot_id = p.bot_id
             order by p.partition_id
             limit ? offset ?
            """;

    /**
     * Flow level unsettled reservations behind the partition budget.
     * Parameters: owner_account_id, bot_id, partition_id, flow_id, limit, offset.
     */
    public static final String FLOW_RESERVATIONS = OWNED_FLOW_SCOPE + """

            select r.id as reservation_id,
                   r.reservation_key,
                   r.intent_id,
                   r.resource_type,
                   r.status,
                   r.currency_code,
                   r.instrument_id,
                   r.reserved_amount,
                   r.consumed_amount,
                   r.released_amount,
                   r.reserved_quantity,
                   r.consumed_quantity,
                   r.released_quantity,
                   r.created_at,
                   r.last_event_sequence
              from trading.resource_reservations r
              join scope s
                on s.bot_id = r.bot_id
               and s.partition_id = r.partition_id
               and s.flow_id = r.flow_id
             order by r.created_at, r.id
             limit ? offset ?
            """;

    /**
     * Flow level positions.
     * Parameters: owner_account_id, bot_id, partition_id, flow_id, limit, offset.
     */
    public static final String FLOW_POSITIONS = OWNED_FLOW_SCOPE + """

            select p.instrument_id,
                   p.long_quantity,
                   p.short_quantity,
                   p.cost_basis_amount,
                   p.last_event_sequence,
                   p.projection_hash,
                   p.updated_at
              from trading.flow_position_projections p
              join scope s
                on s.bot_id = p.bot_id
               and s.partition_id = p.partition_id
               and s.flow_id = p.flow_id
             order by p.instrument_id
             limit ? offset ?
            """;

    /**
     * Partition level positions.
     * Parameters: owner_account_id, bot_id, partition_id, limit, offset.
     */
    public static final String PARTITION_POSITIONS = OWNED_PARTITION_SCOPE + """

            select p.instrument_id,
                   p.net_quantity,
                   p.average_cost,
                   p.realized_pnl,
                   p.last_valuation_price,
                   p.last_valuation_at,
                   p.valuation_status,
                   p.last_bot_event_sequence,
                   p.updated_at
              from trading.partition_position_projections p
              join scope s
                on s.bot_id = p.bot_id
               and s.partition_id = p.partition_id
             order by p.instrument_id
             limit ? offset ?
            """;

    /**
     * Orders that carry at least one component of the requested flow, with their rebuildable state.
     * Parameters: owner_account_id, bot_id, partition_id, flow_id, limit, offset.
     */
    public static final String FLOW_ORDERS = OWNED_FLOW_SCOPE + """

            select o.id as order_id,
                   o.instrument_id,
                   o.order_key,
                   o.side,
                   o.order_type,
                   o.time_in_force,
                   o.requested_quantity,
                   o.limit_price,
                   o.stop_price,
                   o.accepted_at,
                   o.expires_at,
                   sp.status,
                   sp.filled_quantity,
                   sp.remaining_quantity,
                   sp.reserved_cash,
                   sp.reserved_quantity,
                   sp.last_order_event_sequence,
                   sp.updated_at
              from trading.orders o
              join scope s
                on s.bot_id = o.bot_id
               and s.partition_id = o.partition_id
              join trading.order_state_projections sp
                on sp.order_id = o.id
               and sp.bot_id = o.bot_id
               and sp.partition_id = o.partition_id
             where exists (
                       select 1
                         from trading.order_components c
                         join trading.order_intents i
                           on i.id = c.intent_id
                        where c.order_id = o.id
                          and c.bot_id = o.bot_id
                          and c.partition_id = o.partition_id
                          and i.flow_id = s.flow_id
                   )
             order by o.accepted_at, o.id
             limit ? offset ?
            """;

    /**
     * Every individual fill with its exact flow attribution. One row per fill and component
     * allocation, so partial fills are never collapsed into an order level total.
     * Parameters: owner_account_id, bot_id, partition_id, flow_id, limit, offset.
     */
    public static final String FLOW_FILLS = OWNED_FLOW_SCOPE + """

            select f.id as fill_id,
                   f.order_id,
                   f.provider_fill_key,
                   f.quantity,
                   f.reference_price,
                   f.reference_observed_at,
                   f.slippage_rate_bps,
                   f.slippage_amount,
                   f.fill_price,
                   f.gross_amount,
                   f.fee_rate_bps,
                   f.fee_amount,
                   f.settlement_cash_delta,
                   f.occurred_at,
                   a.id as allocation_id,
                   a.allocation_sequence,
                   a.allocated_quantity,
                   a.allocated_gross_amount,
                   a.allocated_fee_amount,
                   a.allocated_settlement_cash_delta
              from trading.fills f
              join scope s
                on s.bot_id = f.bot_id
               and s.partition_id = f.partition_id
              join trading.fill_component_allocations a
                on a.fill_id = f.id
               and a.bot_id = f.bot_id
               and a.partition_id = f.partition_id
              join trading.order_components c
                on c.id = a.order_component_id
               and c.bot_id = a.bot_id
               and c.partition_id = a.partition_id
              join trading.order_intents i
                on i.id = c.intent_id
               and i.flow_id = s.flow_id
             order by f.occurred_at, f.id, a.allocation_sequence
             limit ? offset ?
            """;

    /**
     * Official double entry ledger lines attributed to the flow through the order component that
     * produced them.
     * Parameters: owner_account_id, bot_id, partition_id, flow_id, limit, offset.
     */
    public static final String FLOW_LEDGER_ENTRIES = OWNED_FLOW_SCOPE + """

            select t.id as transaction_id,
                   t.transaction_type,
                   t.transaction_key,
                   t.source_type,
                   t.source_id,
                   t.currency_code,
                   t.reversal_of_transaction_id,
                   t.occurred_at,
                   t.description_code,
                   e.id as entry_id,
                   e.entry_sequence,
                   e.direction,
                   e.amount,
                   e.quantity,
                   la.account_key,
                   la.account_type
              from trading.ledger_transactions t
              join scope s
                on s.bot_id = t.bot_id
               and s.partition_id = t.partition_id
              join trading.ledger_entries e
                on e.transaction_id = t.id
               and e.bot_id = t.bot_id
               and e.partition_id = t.partition_id
              join trading.ledger_accounts la
                on la.id = e.ledger_account_id
               and la.bot_id = e.bot_id
              join trading.order_components c
                on c.id = e.order_component_id
               and c.bot_id = e.bot_id
               and c.partition_id = e.partition_id
              join trading.order_intents i
                on i.id = c.intent_id
               and i.flow_id = s.flow_id
             order by t.occurred_at, t.id, e.entry_sequence
             limit ? offset ?
            """;

    /**
     * Rejection, reduction and settlement reasons for one flow, read from the append only records
     * that already carry them. No reason is copied into a second source of truth.
     * Parameters: owner_account_id, bot_id, partition_id, flow_id, limit, offset.
     */
    public static final String FLOW_REASONS = OWNED_FLOW_SCOPE + """

            select r.reason_id,
                   r.order_id,
                   r.scope_level,
                   r.reason_type,
                   r.reason_code,
                   r.detail,
                   r.occurred_at
              from (
                    select i.id as reason_id,
                           null::uuid as order_id,
                           'FLOW' as scope_level,
                           'REJECTION' as reason_type,
                           i.decision_reason_code as reason_code,
                           i.decision::text as detail,
                           ib.finalized_at as occurred_at
                      from trading.order_intents i
                      join scope s
                        on s.bot_id = i.bot_id
                       and s.partition_id = i.partition_id
                       and s.flow_id = i.flow_id
                      join trading.order_intent_batches ib
                        on ib.id = i.batch_id
                       and ib.bot_id = i.bot_id
                       and ib.partition_id = i.partition_id
                     where i.decision in ('REJECTED', 'CONFLICTED')

                    union all

                    select i.id,
                           null::uuid,
                           'FLOW',
                           'RESIZE',
                           i.decision_reason_code,
                           concat_ws(
                               '/',
                               coalesce(i.requested_quantity, i.requested_notional)::text,
                               i.post_netting_quantity::text,
                               coalesce(i.final_quantity, i.final_notional, 0)::text),
                           ib.finalized_at
                      from trading.order_intents i
                      join scope s
                        on s.bot_id = i.bot_id
                       and s.partition_id = i.partition_id
                       and s.flow_id = i.flow_id
                      join trading.order_intent_batches ib
                        on ib.id = i.batch_id
                       and ib.bot_id = i.bot_id
                       and ib.partition_id = i.partition_id
                     where i.decision in ('REDUCED', 'NETTED')

                    union all

                    select oe.id,
                           oe.order_id,
                           'FLOW',
                           case when oe.new_status = 'REJECTED' then 'REJECTION'
                                else 'SETTLEMENT' end,
                           oe.reason_code,
                           oe.event_type,
                           oe.occurred_at
                      from trading.order_events oe
                      join scope s
                        on s.bot_id = oe.bot_id
                       and s.partition_id = oe.partition_id
                     where oe.reason_code is not null
                       and oe.new_status in ('REJECTED', 'CANCELLED', 'EXPIRED')
                       and exists (
                               select 1
                                 from trading.order_components c
                                 join trading.order_intents i
                                   on i.id = c.intent_id
                                where c.order_id = oe.order_id
                                  and c.bot_id = oe.bot_id
                                  and c.partition_id = oe.partition_id
                                  and i.flow_id = s.flow_id
                           )

                    union all

                    select sca.id,
                           null::uuid,
                           'FLOW',
                           'SETTLEMENT',
                           sca.reason_type::text,
                           sca.reason_document::text,
                           sca.created_at
                      from trading.system_close_actions sca
                      join scope s
                        on s.bot_id = sca.bot_id
                       and s.partition_id = sca.partition_id
                       and s.flow_id = sca.flow_id
                   ) r
             order by r.occurred_at, r.reason_type, r.reason_id
             limit ? offset ?
            """;

    /** Every canonical statement keyed by the fixture block that must prove it. */
    public static final Map<String, String> ALL = Map.ofEntries(
            Map.entry("bot_budget", BOT_BUDGET),
            Map.entry("partition_budgets", PARTITION_BUDGETS),
            Map.entry("flow_reservations", FLOW_RESERVATIONS),
            Map.entry("flow_positions", FLOW_POSITIONS),
            Map.entry("partition_positions", PARTITION_POSITIONS),
            Map.entry("flow_orders", FLOW_ORDERS),
            Map.entry("flow_fills", FLOW_FILLS),
            Map.entry("flow_ledger_entries", FLOW_LEDGER_ENTRIES),
            Map.entry("flow_reasons", FLOW_REASONS));

    /** Statements that must page deterministically. */
    public static final List<String> PAGED = List.of(
            "partition_budgets", "flow_reservations", "flow_positions",
            "partition_positions", "flow_orders", "flow_fills",
            "flow_ledger_entries", "flow_reasons");

    private CanonicalTradingReadSql() {}
}
