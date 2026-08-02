create table trading.execution_fill_record (
    fill_record_id uuid primary key,
    request_fingerprint varchar(64) not null,
    root_fill_id uuid not null,
    correction_of_record_id uuid references trading.execution_fill_record(fill_record_id),
    order_id uuid not null,
    source_execution_id varchar(255) not null,
    revision integer not null,
    kind varchar(16) not null,
    quantity numeric(38,18) not null,
    price numeric(38,18) not null,
    commission numeric(38,18) not null,
    slippage numeric(38,18) not null,
    occurred_at timestamptz not null,
    received_at timestamptz not null,
    unique (order_id, source_execution_id, revision),
    unique (correction_of_record_id),
    check (request_fingerprint ~ '^[0-9a-f]{64}$'),
    check (revision >= 0),
    check (kind in ('ORIGINAL', 'CORRECTION', 'BUST')),
    check (quantity >= 0 and price >= 0 and commission >= 0 and slippage >= 0),
    check (received_at >= occurred_at),
    check ((revision = 0 and kind = 'ORIGINAL' and correction_of_record_id is null and quantity > 0 and price > 0)
        or (revision > 0 and kind = 'CORRECTION' and correction_of_record_id is not null and quantity > 0 and price > 0)
        or (revision > 0 and kind = 'BUST' and correction_of_record_id is not null
            and quantity = 0 and price = 0 and commission = 0 and slippage = 0))
);

create index execution_fill_record_order_time_idx
    on trading.execution_fill_record(order_id, occurred_at, source_execution_id, revision);
