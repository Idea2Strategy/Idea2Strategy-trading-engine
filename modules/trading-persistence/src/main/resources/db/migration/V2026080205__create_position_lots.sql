create table trading.execution_position_lot (
    lot_id uuid primary key, bot_id uuid not null, partition_id uuid not null, flow_id uuid not null,
    instrument_id uuid not null, opening_fill_record_id uuid not null unique,
    opened_quantity numeric(38,18) not null, unit_price numeric(38,18) not null,
    opening_commission numeric(38,18) not null, opened_cost_basis numeric(38,18) not null,
    opened_at timestamptz not null,
    check(opened_quantity>0 and unit_price>0 and opening_commission>=0 and opened_cost_basis>0)
);
create index execution_position_lot_fifo_idx on trading.execution_position_lot
    (bot_id,partition_id,flow_id,instrument_id,opened_at,lot_id);

create table trading.execution_position_lot_projection (
    lot_id uuid primary key references trading.execution_position_lot(lot_id),
    remaining_quantity numeric(38,18) not null, remaining_cost_basis numeric(38,18) not null,
    version bigint not null, closed_at timestamptz, updated_at timestamptz not null,
    check(remaining_quantity>=0 and remaining_cost_basis>=0 and version>0),
    check((remaining_quantity=0)=(closed_at is not null))
);

create table trading.execution_position_lot_movement (
    movement_id uuid primary key, lot_id uuid not null references trading.execution_position_lot(lot_id),
    fill_record_id uuid not null, movement_type varchar(8) not null,
    quantity_delta numeric(38,18) not null, cost_basis_delta numeric(38,18) not null,
    realized_pnl numeric(38,18) not null, remaining_after numeric(38,18) not null,
    cost_basis_after numeric(38,18) not null, occurred_at timestamptz not null,
    unique(fill_record_id,lot_id),
    check(movement_type in ('OPEN','CLOSE')), check(quantity_delta<>0),
    check(remaining_after>=0 and cost_basis_after>=0)
);

create table trading.execution_flow_position_projection (
    bot_id uuid not null, partition_id uuid not null, flow_id uuid not null, instrument_id uuid not null,
    quantity numeric(38,18) not null, cost_basis numeric(38,18) not null,
    realized_pnl numeric(38,18) not null, version bigint not null, updated_at timestamptz not null,
    primary key(bot_id,partition_id,flow_id,instrument_id),
    check(quantity>=0 and cost_basis>=0 and version>0)
);

create table trading.execution_position_command (
    fill_record_id uuid primary key, request_fingerprint varchar(64) not null,
    command_kind varchar(8) not null, quantity_delta numeric(38,18) not null,
    cost_basis_delta numeric(38,18) not null, realized_pnl numeric(38,18) not null,
    ending_quantity numeric(38,18) not null, ending_cost_basis numeric(38,18) not null,
    affected_lots integer not null,
    check(request_fingerprint ~ '^[0-9a-f]{64}$'), check(command_kind in ('OPEN','CLOSE')),
    check(affected_lots>0)
);
