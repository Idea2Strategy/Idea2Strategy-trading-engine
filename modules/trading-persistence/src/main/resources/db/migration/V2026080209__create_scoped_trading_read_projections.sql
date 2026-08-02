create table trading.execution_order_scope (
    order_id uuid primary key references trading.trading_order(order_id),
    bot_id uuid not null,
    partition_id uuid not null,
    flow_id uuid not null,
    attributed_at timestamptz not null,
    unique (order_id, bot_id, partition_id, flow_id)
);

create index execution_order_scope_owner_idx
    on trading.execution_order_scope(bot_id, partition_id, flow_id, order_id);

create table trading.execution_ledger_scope (
    transaction_id uuid primary key references trading.official_ledger_transaction(transaction_id),
    bot_id uuid not null,
    partition_id uuid not null,
    flow_id uuid not null,
    order_id uuid,
    attributed_at timestamptz not null,
    foreign key (order_id, bot_id, partition_id, flow_id)
        references trading.execution_order_scope(order_id, bot_id, partition_id, flow_id)
);

create index execution_ledger_scope_owner_idx
    on trading.execution_ledger_scope(bot_id, partition_id, flow_id, transaction_id);

create table trading.execution_projection_reason (
    reason_id uuid primary key,
    bot_id uuid not null,
    partition_id uuid not null,
    flow_id uuid not null,
    order_id uuid,
    reason_type varchar(16) not null,
    reason_code varchar(128) not null,
    detail text not null,
    occurred_at timestamptz not null,
    check (reason_type in ('REJECTION', 'RESIZE', 'SETTLEMENT')),
    check (length(btrim(reason_code)) > 0 and length(btrim(detail)) > 0),
    foreign key (order_id, bot_id, partition_id, flow_id)
        references trading.execution_order_scope(order_id, bot_id, partition_id, flow_id)
);

create index execution_projection_reason_owner_time_idx
    on trading.execution_projection_reason(bot_id, partition_id, flow_id, occurred_at, reason_id);
