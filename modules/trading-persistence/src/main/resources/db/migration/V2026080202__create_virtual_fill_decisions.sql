create table trading.virtual_fill_decision (
    decision_id uuid primary key,
    request_fingerprint varchar(64) not null,
    order_id uuid not null,
    order_version bigint not null check (order_version > 0),
    snapshot_id uuid not null,
    instrument_id uuid not null,
    observed_at timestamptz(6) not null,
    bid_price numeric(38,18) not null check (bid_price > 0),
    bid_size numeric(38,18) not null check (bid_size >= 0),
    ask_price numeric(38,18) not null check (ask_price > 0),
    ask_size numeric(38,18) not null check (ask_size >= 0),
    last_trade_price numeric(38,18) not null check (last_trade_price > 0),
    last_trade_size numeric(38,18) not null check (last_trade_size >= 0),
    trailing_reference_price numeric(38,18),
    eligibility varchar(48) not null,
    fill_id uuid,
    fill_quantity numeric(38,18),
    reference_price numeric(38,18),
    fill_price numeric(38,18),
    notional numeric(38,18),
    slippage_amount numeric(38,18),
    fee numeric(38,18),
    partial boolean,
    evaluated_at timestamptz(6) not null,
    created_at timestamptz(6) not null default current_timestamp,
    unique (order_id, order_version, snapshot_id),
    check (bid_price <= ask_price),
    check (trailing_reference_price is null or trailing_reference_price > 0),
    check ((eligibility = 'ELIGIBLE' and fill_id is not null and fill_quantity > 0
            and reference_price > 0 and fill_price > 0 and notional > 0
            and slippage_amount >= 0 and fee >= 0 and partial is not null)
        or (eligibility <> 'ELIGIBLE' and fill_id is null and fill_quantity is null
            and reference_price is null and fill_price is null and notional is null
            and slippage_amount is null and fee is null and partial is null))
);

create index virtual_fill_decision_order_idx
    on trading.virtual_fill_decision (order_id, order_version, observed_at);
