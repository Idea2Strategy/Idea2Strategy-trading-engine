create table trading.bot_stop_settlement (
    settlement_id uuid primary key,
    bot_id uuid not null unique,
    stop_reason varchar(32) not null,
    reason_detail text not null,
    checkpoint varchar(32) not null,
    version bigint not null,
    requested_at timestamptz not null,
    updated_at timestamptz not null,
    failed_step varchar(40),
    terminal_reason text,
    check (stop_reason in ('USER_REQUEST', 'ACCOUNT_SUSPENDED', 'POLICY_FORCED')),
    check (checkpoint in ('REQUESTED', 'WORK_BLOCKED', 'ORDERS_CLEANED', 'LIQUIDATING', 'STOPPED', 'SETTLEMENT_FAILED')),
    check (version > 0 and updated_at >= requested_at),
    check ((checkpoint = 'SETTLEMENT_FAILED' and failed_step is not null and terminal_reason is not null)
        or (checkpoint <> 'SETTLEMENT_FAILED' and failed_step is null and terminal_reason is null))
);

create table trading.bot_stop_attempt (
    settlement_id uuid not null references trading.bot_stop_settlement(settlement_id),
    resulting_version bigint not null,
    operation_id uuid not null,
    step varchar(40) not null,
    result_status varchar(24) not null,
    detail text not null,
    occurred_at timestamptz not null,
    primary key (settlement_id, resulting_version),
    check (step in ('BLOCK_NEW_WORK', 'CANCEL_ORDERS_AND_RELEASE', 'LIQUIDATE_POSITIONS')),
    check (result_status in ('COMPLETED', 'PARTIAL', 'RETRYABLE', 'TERMINAL_FAILURE'))
);

create index bot_stop_attempt_operation_idx on trading.bot_stop_attempt(operation_id);

create table trading.bot_stop_event (
    settlement_id uuid not null references trading.bot_stop_settlement(settlement_id),
    version bigint not null,
    from_checkpoint varchar(32),
    to_checkpoint varchar(32) not null,
    occurred_at timestamptz not null,
    reason text,
    primary key (settlement_id, version)
);
