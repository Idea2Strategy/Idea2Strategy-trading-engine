create table trading.execution_resource_reservation (
    reservation_id uuid primary key,
    create_command_id uuid not null unique,
    request_fingerprint varchar(64) not null,
    order_id uuid not null,
    resource_type varchar(32) not null,
    resource_key varchar(64) not null,
    reserved numeric(38,18) not null,
    consumed numeric(38,18) not null default 0,
    released numeric(38,18) not null default 0,
    status varchar(16) not null,
    version bigint not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    terminal_reason text,
    unique (order_id, resource_type, resource_key),
    check (request_fingerprint ~ '^[0-9a-f]{64}$'),
    check (resource_type in ('CASH_BUYING_POWER', 'POSITION_QUANTITY')),
    check (status in ('ACTIVE', 'SETTLED', 'RELEASED')),
    check (reserved > 0 and consumed >= 0 and released >= 0 and consumed + released <= reserved),
    check (version > 0 and updated_at >= created_at),
    check ((status = 'ACTIVE' and consumed + released < reserved and terminal_reason is null)
        or (status = 'SETTLED' and consumed > 0 and consumed + released = reserved)
        or (status = 'RELEASED' and consumed = 0 and released = reserved and terminal_reason is not null))
);

create table trading.execution_resource_reservation_lot (
    reservation_id uuid not null references trading.execution_resource_reservation(reservation_id) on delete cascade,
    lot_id uuid not null,
    opened_at timestamptz not null,
    reserved numeric(38,18) not null,
    consumed numeric(38,18) not null default 0,
    released numeric(38,18) not null default 0,
    primary key (reservation_id, lot_id),
    check (reserved > 0 and consumed >= 0 and released >= 0 and consumed + released <= reserved)
);

create table trading.execution_resource_reservation_command (
    command_id uuid primary key,
    request_fingerprint varchar(64) not null,
    reservation_id uuid not null references trading.execution_resource_reservation(reservation_id),
    resulting_version bigint not null,
    result_status varchar(16) not null,
    unique (reservation_id, resulting_version),
    check (request_fingerprint ~ '^[0-9a-f]{64}$'),
    check (resulting_version > 0),
    check (result_status in ('ACTIVE', 'SETTLED', 'RELEASED'))
);

create table trading.execution_resource_reservation_movement (
    reservation_id uuid not null references trading.execution_resource_reservation(reservation_id),
    version bigint not null,
    command_id uuid not null unique references trading.execution_resource_reservation_command(command_id),
    movement_type varchar(16) not null,
    amount numeric(38,18) not null,
    occurred_at timestamptz not null,
    reason text,
    primary key (reservation_id, version),
    check (version > 0 and amount > 0),
    check (movement_type in ('RESERVE', 'CONSUME', 'INCREASE', 'DECREASE', 'RELEASE')),
    check ((movement_type = 'RELEASE' and reason is not null) or (movement_type <> 'RELEASE' and reason is null))
);

create index execution_resource_reservation_order_idx
    on trading.execution_resource_reservation(order_id);
